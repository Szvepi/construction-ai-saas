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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailClassificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailClassificationService.class);
    private static final int MAX_FIELD_LENGTH = 500;
    private static final int MAX_BODY_HARD_LIMIT = 20_000;

    private final EmailRepository emails;
    private final GmailConnectionRepository connections;
    private final EmailClassificationModel model;
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
            AppProperties appProperties,
            ObjectMapper objectMapper,
            Clock clock,
            JdbcTemplate jdbcTemplate,
            @Qualifier("emailClassificationExecutor") TaskExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.emails = emails;
        this.connections = connections;
        this.model = model;
        this.properties = appProperties.getEmailClassification();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
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
            email.setCategory(EmailCategory.OTHER);
            email.setClassificationStatus("PENDING");
            email.setClassificationSource("PENDING");
            email.setClassificationVersion(null);
            email.setClassificationReason(null);
            email.setClassificationEvidence(null);
            email.setClassificationAttempts(0);
            email.setClassificationRetryAt(now);
            email.setClassificationLastAttemptAt(null);
        }
        emails.saveAll(candidates);
        return candidates.size();
    }

    @Scheduled(fixedDelayString = "${app.email-classification.poll-interval-ms:30000}")
    public void classifyDueEmails() {
        if (!properties.isEnabled()) {
            return;
        }
        int processed = 0;
        while (processed < properties.getMaxPerRun()) {
            Optional<ClaimedEmail> claim = claimNext();
            if (claim.isEmpty()) {
                return;
            }
            processed++;
            try {
                executor.execute(() -> classify(claim.get()));
            } catch (RuntimeException ex) {
                retryOrFail(claim.get(), ex);
            }
        }
    }

    private Optional<ClaimedEmail> claimNext() {
        return transactions.execute(status -> claimNextInTransaction());
    }

    private Optional<ClaimedEmail> claimNextInTransaction() {
        Instant now = clock.instant();
        Instant staleBefore = now.minusSeconds(Math.max(1, properties.getStaleProcessingMinutes()) * 60L);
        Optional<Email> candidate = emails.lockNextClassificationDue(now, staleBefore);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Email email = candidate.get();
        boolean hasContext = hasText(email.getSubject()) || hasText(email.getFromAddress())
                || hasText(email.getBodyText()) || hasText(email.getClassificationMetadata());
        if (hasContext && !reserveDailyBudget(LocalDate.now(clock))) {
            return Optional.empty();
        }
        email.setClassificationAttempts(email.getClassificationAttempts() + 1);
        email.setClassificationLastAttemptAt(now);
        email.setClassificationRetryAt(null);
        email.setClassificationStatus("PROCESSING");
        emails.save(email);
        return Optional.of(new ClaimedEmail(
                email.getId(),
                email.getSubject(),
                email.getFromAddress(),
                email.getClassificationMetadata(),
                truncate(email.getBodyText(), effectiveBodyLimit()),
                email.getClassificationAttempts(),
                hasContext));
    }

    private void classify(ClaimedEmail email) {
        if (!email.hasContext()) {
            finish(email.id(), new Decision(
                    EmailCategory.CLASSIFICATION_REVIEW,
                    "Nincs elegendő adat a levél szándékának megállapításához.",
                    ""));
            return;
        }
        try {
            String context = objectMapper.writeValueAsString(new ModelInput(
                    email.subject(), email.fromAddress(), email.metadata(), email.body()));
            Decision decision = parseDecision(model.classify(context));
            finish(email.id(), decision);
        } catch (Exception ex) {
            retryOrFail(email, ex);
        }
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

    void finish(Long emailId, Decision decision) {
        transactions.executeWithoutResult(status -> {
            Email email = emails.findById(emailId).orElseThrow();
            if ("MANUAL".equals(email.getClassificationSource())) {
                return;
            }
            email.setCategory(decision.category());
            email.setClassificationSource("AI");
            email.setClassificationStatus(decision.category() == EmailCategory.CLASSIFICATION_REVIEW
                    ? "REVIEW" : "CLASSIFIED");
            email.setClassificationVersion(properties.getVersion());
            email.setClassificationReason(decision.reason());
            email.setClassificationEvidence(decision.evidence());
            email.setClassificationRetryAt(null);
            emails.save(email);
            log.info("EMAIL_CLASSIFIED emailId={} category={} version={}",
                    emailId, decision.category(), properties.getVersion());
        });
    }

    void retryOrFail(ClaimedEmail claim, Exception exception) {
        transactions.executeWithoutResult(status -> {
            Email email = emails.findById(claim.id()).orElseThrow();
            if ("MANUAL".equals(email.getClassificationSource())) {
                return;
            }
            if (claim.attempts() >= properties.getMaxAttempts()) {
                email.setCategory(EmailCategory.CLASSIFICATION_REVIEW);
                email.setClassificationSource("AI");
                email.setClassificationStatus("FAILED");
                email.setClassificationVersion(null);
                email.setClassificationReason("Az automatikus besorolás nem sikerült; kézi ellenőrzés szükséges.");
                email.setClassificationEvidence(null);
                email.setClassificationRetryAt(null);
            } else {
                long multiplier = 1L << Math.min(20, Math.max(0, claim.attempts() - 1));
                long delay = Math.min(86_400L, Math.max(1, properties.getRetryBaseSeconds()) * multiplier);
                email.setClassificationStatus("RETRY");
                email.setClassificationRetryAt(clock.instant().plusSeconds(delay));
            }
            emails.save(email);
            log.warn("EMAIL_CLASSIFICATION_FAILED emailId={} attempt={} retryScheduled={} errorCategory={}",
                    email.getId(), claim.attempts(), email.getClassificationStatus().equals("RETRY"),
                    exception.getClass().getSimpleName());
        });
    }

    private boolean reserveDailyBudget(LocalDate date) {
        List<Integer> reservations = jdbcTemplate.query(
                """
                INSERT INTO email_classification_daily_budget (budget_date, attempts)
                VALUES (?, 1)
                ON CONFLICT (budget_date) DO UPDATE
                SET attempts = email_classification_daily_budget.attempts + 1
                WHERE email_classification_daily_budget.attempts < ?
                RETURNING attempts
                """,
                (resultSet, rowNum) -> resultSet.getInt(1),
                Date.valueOf(date),
                properties.getMaxPerDay());
        return !reservations.isEmpty();
    }

    private int effectiveBodyLimit() {
        return Math.max(1, Math.min(MAX_BODY_HARD_LIMIT, properties.getMaxBodyCharacters()));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "\n[levél törzsének további része kihagyva]";
    }

    private static String boundedText(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= MAX_FIELD_LENGTH ? trimmed : trimmed.substring(0, MAX_FIELD_LENGTH);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record ClaimedEmail(
            Long id,
            String subject,
            String fromAddress,
            String metadata,
            String body,
            int attempts,
            boolean hasContext) {
    }

    record Decision(EmailCategory category, String reason, String evidence) {
    }

    private record ModelInput(String subject, String from, String metadata, String body) {
    }
}
