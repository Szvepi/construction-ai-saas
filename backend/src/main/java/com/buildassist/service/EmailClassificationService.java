package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.model.GmailConnection;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailClassificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailClassificationService.class);
    private static final int MAX_FIELD_LENGTH = 500;
    private static final int MAX_METADATA_LENGTH = 5_000;

    private final EmailRepository emails;
    private final GmailConnectionRepository connections;
    private final EmailClassificationModel model;
    private final EmailClassificationBudgetService budgets;
    private final AppProperties.EmailClassification properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;

    public EmailClassificationService(
            EmailRepository emails,
            GmailConnectionRepository connections,
            EmailClassificationModel model,
            EmailClassificationBudgetService budgets,
            AppProperties appProperties,
            ObjectMapper objectMapper,
            Clock clock,
            JdbcTemplate jdbcTemplate,
            @Qualifier("emailClassificationExecutor") TaskExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.emails = emails;
        this.connections = connections;
        this.model = model;
        this.budgets = budgets;
        this.properties = appProperties.getEmailClassification();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
        if (properties.getMaxPerRun() < 1
                || properties.getMaxAttempts() < 1
                || properties.getRetryBaseSeconds() < 1
                || properties.getRetryMaxSeconds() < 1
                || properties.getMetricsIntervalMs() < 1
                || properties.getMaxInputTokens() < 1
                || properties.getMaxOutputTokens() < 1) {
            throw new IllegalArgumentException("Invalid email classification scheduler configuration");
        }
    }

    @Transactional
    public int enqueueReclassification(Long userId, int requestedLimit) {
        int maxLimit = properties.getMaxReclassificationBatch();
        if (requestedLimit < 1 || requestedLimit > maxLimit) {
            throw new IllegalArgumentException("limit must be between 1 and " + maxLimit);
        }
        GmailConnection connection = connections.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Gmail not connected for user " + userId));
        List<Email> candidates = emails.findReclassificationCandidates(
                connection.getId(), properties.getVersion(), PageRequest.of(0, requestedLimit));
        Instant now = clock.instant();
        for (Email email : candidates) {
            email.setCategory(EmailCategory.UNCLASSIFIED);
            email.setClassificationStatus("PENDING");
            email.setClassificationSource("PENDING");
            email.setClassificationVersion(null);
            email.setClassificationReason(null);
            email.setClassificationEvidence(null);
            email.setClassificationRuleId(null);
            email.setClassificationAttempts(0);
            email.setClassificationRetryAt(now);
            email.setClassificationLastAttemptAt(null);
            email.setClassificationQueueType("RECLASSIFICATION");
            email.setClassificationOrigin("RECLASSIFICATION");
            email.setClassificationQueuedAt(now);
            email.setClassificationStartedAt(null);
            email.setClassificationCompletedAt(null);
            email.setClassificationActiveAttemptId(null);
        }
        emails.saveAll(candidates);
        return candidates.size();
    }

    @Scheduled(fixedDelayString = "${app.email-classification.poll-interval-ms:30000}")
    public void classifyDueEmails() {
        if (!properties.isEnabled()) {
            return;
        }
        int claimed = 0;
        while (claimed < properties.getMaxPerRun()) {
            Optional<ClaimedEmail> claim = claimNext();
            if (claim.isEmpty()) {
                return;
            }
            claimed++;
            try {
                executor.execute(() -> classify(claim.get()));
            } catch (RuntimeException ex) {
                releaseClaim(claim.get(), ex);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.email-classification.metrics-interval-ms:300000}")
    public void logClassificationMetrics() {
        if (!properties.isEnabled()) {
            return;
        }
        Date budgetDate = Date.valueOf(budgets.currentBudgetDate());
        List<Map<String, Object>> globalRows = jdbcTemplate.queryForList("""
                SELECT attempts, successful_calls, failed_calls, classification_failures, prefiltered_emails,
                       input_tokens, output_tokens, estimated_cost_usd, limit_hits
                FROM email_classification_daily_budget
                WHERE budget_date = ?
                """, budgetDate);
        Map<String, Object> global = globalRows.isEmpty() ? Map.of() : globalRows.get(0);
        long pending = emails.countQueuedForClassification();
        Double averageDelaySeconds = jdbcTemplate.queryForObject("""
                SELECT AVG(EXTRACT(EPOCH FROM (classification_completed_at - classification_queued_at)))
                FROM emails
                WHERE classification_completed_at >= ?
                  AND classification_source IN ('AI', 'RULE')
                  AND classification_status IN ('CLASSIFIED', 'REVIEW', 'FAILED')
                """, Double.class, budgets.currentBudgetDate()
                .atStartOfDay(java.time.ZoneId.of(properties.getDailyBudgetTimeZone()))
                .toInstant().atOffset(java.time.ZoneOffset.UTC));

        log.info(
                "EMAIL_CLASSIFICATION_METRICS date={} processed={} aiStarted={} aiSucceeded={} aiFailed={} "
                        + "classificationFailures={} aiFree={} inputTokens={} outputTokens={} "
                        + "estimatedCostUsd={} pending={} averageDelaySeconds={} dailyLimitHits={}",
                budgetDate,
                number(global, "successful_calls") + number(global, "failed_calls")
                        + number(global, "prefiltered_emails"),
                number(global, "attempts"),
                number(global, "successful_calls"),
                number(global, "failed_calls"),
                number(global, "classification_failures"),
                number(global, "prefiltered_emails"),
                number(global, "input_tokens"),
                number(global, "output_tokens"),
                global.getOrDefault("estimated_cost_usd", "0"),
                pending,
                averageDelaySeconds == null ? 0 : averageDelaySeconds,
                number(global, "limit_hits"));

        List<Map<String, Object>> userRows = jdbcTemplate.queryForList("""
                SELECT user_id, attempts, successful_calls, failed_calls, prefiltered_emails,
                       input_tokens, output_tokens, estimated_cost_usd, limit_hits
                FROM email_classification_user_daily_budget
                WHERE budget_date = ?
                ORDER BY user_id
                """, budgetDate);
        for (Map<String, Object> user : userRows) {
            log.info(
                    "EMAIL_CLASSIFICATION_USER_METRICS date={} userId={} aiStarted={} aiSucceeded={} aiFailed={} "
                            + "aiFree={} inputTokens={} outputTokens={} estimatedCostUsd={} dailyLimitHits={}",
                    budgetDate,
                    user.get("user_id"),
                    number(user, "attempts"),
                    number(user, "successful_calls"),
                    number(user, "failed_calls"),
                    number(user, "prefiltered_emails"),
                    number(user, "input_tokens"),
                    number(user, "output_tokens"),
                    user.get("estimated_cost_usd"),
                    number(user, "limit_hits"));
        }
    }

    private Optional<ClaimedEmail> claimNext() {
        return transactions.execute(status -> claimNextInTransaction());
    }

    private Optional<ClaimedEmail> claimNextInTransaction() {
        Instant now = clock.instant();
        Instant staleBefore = now.minusSeconds(Math.max(1, properties.getStaleProcessingMinutes()) * 60L);
        Instant freshPriorityBefore = now.minusSeconds(
                Math.max(0, properties.getFreshPriorityMinutes()) * 60L);
        Optional<Email> candidate = emails.lockNextClassificationDue(
                now,
                staleBefore,
                Date.valueOf(budgets.currentBudgetDate()),
                properties.getMaxPerUserPerDay(),
                freshPriorityBefore);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Email email = candidate.get();
        String previousStatus = email.getClassificationStatus();
        Long userId = email.getGmailConnection().getUser().getId();
        if ("PROCESSING".equals(previousStatus)) {
            budgets.markStale(email.getClassificationActiveAttemptId());
            previousStatus = email.getClassificationAttempts() > 0 ? "RETRY" : "PENDING";
            email.setClassificationQueueType("RETRY");
        }
        emails.touchUserClassificationClaim(Date.valueOf(budgets.currentBudgetDate()), userId);

        boolean hasContext = hasText(email.getSubject()) || hasText(email.getFromAddress())
                || hasText(email.getBodyText()) || hasUsefulMetadata(email.getClassificationMetadata());
        email.setClassificationStartedAt(now);
        email.setClassificationRetryAt(null);
        email.setClassificationStatus("PROCESSING");
        email.setClassificationActiveAttemptId(null);
        emails.save(email);
        return Optional.of(new ClaimedEmail(
                email.getId(),
                userId,
                email.getSubject(),
                email.getFromAddress(),
                email.getClassificationMetadata(),
                email.getBodyText(),
                email.getClassificationAttempts(),
                email.getClassificationQueueType(),
                email.getClassificationOrigin(),
                previousStatus,
                hasContext));
    }

    private void classify(ClaimedEmail claim) {
        if (!claim.hasContext()) {
            finishWithoutAi(claim, "NO_CONTEXT_REVIEW_V1",
                    EmailCategory.CLASSIFICATION_REVIEW,
                    "Nincs elegendő adat a levél szándékának megállapításához.");
            return;
        }

        EmailClassificationPrefilter.Result prefilter =
                EmailClassificationPrefilter.evaluate(claim.metadata(), objectMapper);
        if (prefilter.excluded()) {
            finishWithoutAi(claim, prefilter.ruleId(), EmailCategory.OTHER, prefilter.reason());
            return;
        }

        int attemptNumber = claim.attempts() + 1;
        if (attemptNumber > properties.getMaxAttempts()) {
            finishWithoutAi(claim, "ATTEMPTS_EXHAUSTED_REVIEW_V1",
                    EmailCategory.CLASSIFICATION_REVIEW,
                    "Az automatikus besorolási próbálkozások kimerültek; kézi ellenőrzés szükséges.");
            return;
        }

        PreparedModelInput prepared;
        try {
            prepared = prepareModelInput(claim);
        } catch (Exception ex) {
            finishWithoutAi(claim, "INPUT_PREPARATION_REVIEW_V1",
                    EmailCategory.CLASSIFICATION_REVIEW,
                    "Az e-mail biztonságos AI-bemenetté alakítása nem sikerült; kézi ellenőrzés szükséges.");
            log.warn("EMAIL_CLASSIFICATION_INPUT_PREPARATION_FAILED emailId={} errorCategory={}",
                    claim.id(), ex.getClass().getSimpleName());
            return;
        }

        EmailClassificationBudgetService.ReservationResult reservationResult;
        try {
            reservationResult = budgets.reserve(
                    claim.id(),
                    claim.userId(),
                    claim.queueType(),
                    claim.originType(),
                    attemptNumber,
                    prepared.inputTokens(),
                    properties.getMaxOutputTokens());
        } catch (RuntimeException ex) {
            releaseClaim(claim, ex);
            return;
        }
        if (!reservationResult.allowed()) {
            if (!"CLAIM_NOT_ACTIVE".equals(reservationResult.denialReason())) {
                deferUntilNextBudgetPeriod(claim, reservationResult.denialReason());
            }
            return;
        }

        EmailClassificationBudgetService.Reservation reservation = reservationResult.reservation();
        EmailClassificationModel.ClassificationResponse response = null;
        Decision decision;
        try {
            response = model.classify(prepared.context());
            decision = parseDecision(response == null ? null : response.content());
        } catch (Exception ex) {
            int inputTokens = response != null && response.hasUsage()
                    ? response.inputTokens()
                    : reservation.reservedInputTokens();
            int outputTokens = response != null && response.hasUsage()
                    ? response.outputTokens()
                    : response == null
                        ? reservation.reservedOutputTokens()
                        : OpenAiTokenCounter.count(response.content());
            String failureCategory = response == null ? "model_call" : "invalid_response";
            recordFailure(
                    claim, reservation, inputTokens, outputTokens,
                    response != null && response.hasUsage(), failureCategory, ex);
            return;
        }

        int inputTokens = response.hasUsage()
                ? response.inputTokens()
                : prepared.inputTokens();
        int outputTokens = response.hasUsage()
                ? response.outputTokens()
                : OpenAiTokenCounter.count(response.content());
        boolean usageReported = response.hasUsage();
        transactions.executeWithoutResult(status -> {
            Email email = emails.findByIdForClassificationUpdate(reservation.emailId()).orElseThrow();
            budgets.complete(
                    reservation, inputTokens, outputTokens,
                    usageReported, true, null);
            finishDecision(email, reservation, decision);
        });
    }

    private PreparedModelInput prepareModelInput(ClaimedEmail email) throws Exception {
        ModelInput base = new ModelInput(
                boundedText(email.subject()),
                boundedText(email.fromAddress()),
                boundedMetadata(email.metadata()),
                "");
        String baseContext = objectMapper.writeValueAsString(base);
        int inputLimit = properties.getMaxInputTokens();
        int bodyBudget = Math.max(0,
                inputLimit - OpenAiTokenCounter.count(SpringAiEmailClassificationModel.SYSTEM_PROMPT)
                        - OpenAiTokenCounter.count(baseContext) - 16);
        String body = EmailModelInputPreparer.prepare(email.body(), bodyBudget);

        for (int attempt = 0; attempt < 4; attempt++) {
            String context = objectMapper.writeValueAsString(new ModelInput(
                    base.subject(), base.from(), base.metadata(), body));
            int tokens = OpenAiTokenCounter.count(
                    SpringAiEmailClassificationModel.SYSTEM_PROMPT + "\n" + context) + 8;
            if (tokens <= inputLimit) {
                return new PreparedModelInput(context, tokens);
            }
            bodyBudget = Math.max(0, bodyBudget - (tokens - inputLimit) - 2);
            body = EmailModelInputPreparer.prepare(email.body(), bodyBudget);
        }

        throw new IllegalArgumentException("classification_input_exceeds_token_limit");
    }

    private Decision parseDecision(String response) throws Exception {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("empty_model_response");
        }
        String normalized = response.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        JsonNode json = objectMapper.readTree(normalized);
        EmailCategory category;
        try {
            category = EmailCategory.valueOf(json.path("category").asText(""));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid_model_category");
        }
        if (category != EmailCategory.QUOTE_REQUEST
                && category != EmailCategory.OTHER
                && category != EmailCategory.CLASSIFICATION_REVIEW) {
            throw new IllegalArgumentException("unsupported_model_category");
        }
        String reason = boundedText(json.path("reason").asText(""));
        String evidence = boundedText(json.path("evidence").asText(""));
        if (reason.isBlank()) {
            throw new IllegalArgumentException("missing_model_reason");
        }
        return new Decision(category, reason, evidence);
    }

    private void finishWithoutAi(
            ClaimedEmail claim,
            String ruleId,
            EmailCategory category,
            String reason) {
        transactions.executeWithoutResult(status -> {
            Email email = emails.findByIdForClassificationUpdate(claim.id()).orElseThrow();
            if (!"PROCESSING".equals(email.getClassificationStatus())
                    || "MANUAL".equals(email.getClassificationSource())) {
                return;
            }
            email.setCategory(category);
            email.setClassificationSource("RULE");
            email.setClassificationRuleId(ruleId);
            email.setClassificationStatus(category == EmailCategory.CLASSIFICATION_REVIEW
                    ? "REVIEW" : "CLASSIFIED");
            email.setClassificationVersion(properties.getVersion());
            email.setClassificationReason(reason);
            email.setClassificationEvidence(ruleId);
            email.setClassificationRetryAt(null);
            email.setClassificationActiveAttemptId(null);
            email.setClassificationCompletedAt(clock.instant());
            emails.save(email);
            budgets.recordPrefilter(claim.userId());
            log.info("EMAIL_CLASSIFIED_WITHOUT_AI emailId={} ruleId={} category={}",
                    email.getId(), ruleId, category);
        });
    }

    private void finishDecision(
            Email email,
            EmailClassificationBudgetService.Reservation reservation,
            Decision decision) {
        if (!reservation.attemptId().equals(email.getClassificationActiveAttemptId())
                || "MANUAL".equals(email.getClassificationSource())) {
            return;
        }
        email.setCategory(decision.category());
        email.setClassificationSource("AI");
        email.setClassificationRuleId(null);
        email.setClassificationStatus(decision.category() == EmailCategory.CLASSIFICATION_REVIEW
                ? "REVIEW" : "CLASSIFIED");
        email.setClassificationVersion(properties.getVersion());
        email.setClassificationReason(decision.reason());
        email.setClassificationEvidence(decision.evidence());
        email.setClassificationRetryAt(null);
        email.setClassificationActiveAttemptId(null);
        email.setClassificationCompletedAt(clock.instant());
        emails.save(email);
        log.info("EMAIL_CLASSIFIED emailId={} category={} version={}",
                email.getId(), decision.category(), properties.getVersion());
    }

    private void recordFailure(
            ClaimedEmail claim,
            EmailClassificationBudgetService.Reservation reservation,
            int inputTokens,
            int outputTokens,
            boolean usageReported,
            String failureCategory,
            Exception exception) {
        transactions.executeWithoutResult(status -> {
            Email email = emails.findByIdForClassificationUpdate(claim.id()).orElseThrow();
            budgets.complete(
                    reservation, inputTokens, outputTokens, usageReported, false, failureCategory);
            if (!reservation.attemptId().equals(email.getClassificationActiveAttemptId())
                    || "MANUAL".equals(email.getClassificationSource())) {
                return;
            }
            if (reservation.attemptNumber() >= properties.getMaxAttempts()) {
                email.setCategory(EmailCategory.CLASSIFICATION_REVIEW);
                email.setClassificationSource("AI");
                email.setClassificationStatus("FAILED");
                email.setClassificationVersion(null);
                email.setClassificationReason("Az automatikus besorolás nem sikerült; kézi ellenőrzés szükséges.");
                email.setClassificationEvidence(null);
                email.setClassificationRetryAt(null);
                email.setClassificationCompletedAt(clock.instant());
            } else {
                long multiplier = 1L << Math.min(20, Math.max(0, reservation.attemptNumber() - 1));
                long delay = Math.min(
                        properties.getRetryMaxSeconds(),
                        (long) properties.getRetryBaseSeconds() * multiplier);
                email.setClassificationStatus("RETRY");
                email.setClassificationQueueType("RETRY");
                email.setClassificationRetryAt(clock.instant().plusSeconds(delay));
            }
            email.setClassificationActiveAttemptId(null);
            emails.save(email);
            log.warn("EMAIL_CLASSIFICATION_FAILED emailId={} attempt={} retryScheduled={} errorCategory={}",
                    email.getId(),
                    reservation.attemptNumber(),
                    "RETRY".equals(email.getClassificationStatus()),
                    exception.getClass().getSimpleName());
        });
    }

    private void deferUntilNextBudgetPeriod(ClaimedEmail claim, String reason) {
        transactions.executeWithoutResult(status -> {
            Email email = emails.findByIdForClassificationUpdate(claim.id()).orElseThrow();
            if (!"PROCESSING".equals(email.getClassificationStatus())
                    || "MANUAL".equals(email.getClassificationSource())) {
                return;
            }
            email.setClassificationStatus(claim.previousStatus());
            email.setClassificationRetryAt(budgets.nextBudgetPeriod());
            email.setClassificationStartedAt(null);
            email.setClassificationActiveAttemptId(null);
            emails.save(email);
            log.info("EMAIL_CLASSIFICATION_DEFERRED emailId={} reason={} nextPeriod={}",
                    email.getId(), reason, email.getClassificationRetryAt());
        });
    }

    private void releaseClaim(ClaimedEmail claim, RuntimeException exception) {
        transactions.executeWithoutResult(status -> {
            Email email = emails.findByIdForClassificationUpdate(claim.id()).orElseThrow();
            if (!"PROCESSING".equals(email.getClassificationStatus())
                    || "MANUAL".equals(email.getClassificationSource())) {
                return;
            }
            email.setClassificationStatus(claim.previousStatus());
            email.setClassificationRetryAt(clock.instant().plusSeconds(30));
            email.setClassificationStartedAt(null);
            email.setClassificationActiveAttemptId(null);
            emails.save(email);
            log.warn("EMAIL_CLASSIFICATION_CLAIM_RELEASED emailId={} errorCategory={}",
                    email.getId(), exception.getClass().getSimpleName());
        });
    }

    private String boundedMetadata(String value) {
        String normalized = value == null ? "{}" : value;
        return normalized.length() <= MAX_METADATA_LENGTH
                ? normalized : normalized.substring(0, MAX_METADATA_LENGTH);
    }

    private static String boundedText(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= MAX_FIELD_LENGTH ? trimmed : trimmed.substring(0, MAX_FIELD_LENGTH);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasUsefulMetadata(String value) {
        if (!hasText(value)) {
            return false;
        }
        try {
            JsonNode metadata = objectMapper.readTree(value);
            if (metadata == null || !metadata.isObject()) {
                return false;
            }
            return metadata.properties().stream()
                    .anyMatch(entry -> hasText(entry.getValue().asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return false;
        }
    }

    private static long number(Map<String, Object> row, String name) {
        Object value = row.get(name);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    record ClaimedEmail(
            Long id,
            Long userId,
            String subject,
            String fromAddress,
            String metadata,
            String body,
            int attempts,
            String queueType,
            String originType,
            String previousStatus,
            boolean hasContext) {
    }

    record Decision(EmailCategory category, String reason, String evidence) {
    }

    private record ModelInput(String subject, String from, String metadata, String body) {
    }

    private record PreparedModelInput(String context, int inputTokens) {
    }
}
