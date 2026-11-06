package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailClassificationBudgetService {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final JdbcTemplate jdbc;
    private final AppProperties.EmailClassification properties;
    private final Clock clock;
    private final ZoneId budgetZone;
    private final TransactionTemplate transactions;

    public EmailClassificationBudgetService(
            JdbcTemplate jdbc,
            AppProperties appProperties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = appProperties.getEmailClassification();
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        validateConfiguration();
        this.budgetZone = ZoneId.of(properties.getDailyBudgetTimeZone());
    }

    public ReservationResult reserve(
            Long emailId,
            Long userId,
            String queueType,
            String originType,
            int attemptNumber,
            int inputTokens,
            int maxOutputTokens) {
        LocalDate budgetDate = LocalDate.now(clock.withZone(budgetZone));
        Date sqlDate = Date.valueOf(budgetDate);
        int reservedInputTokens = Math.max(0, inputTokens);
        int reservedOutputTokens = Math.max(0, maxOutputTokens);
        BigDecimal reservedCost = estimateCost(reservedInputTokens, reservedOutputTokens);

        return transactions.execute(status -> {
            List<Boolean> activeClaims = jdbc.query("""
                    SELECT classification_status = 'PROCESSING'
                       AND classification_source <> 'MANUAL'
                    FROM emails
                    WHERE id = ?
                    FOR UPDATE
                    """, (rs, rowNum) -> rs.getBoolean(1), emailId);
            if (activeClaims.isEmpty() || !activeClaims.get(0)) {
                return ReservationResult.denied("CLAIM_NOT_ACTIVE");
            }

            jdbc.update("""
                    INSERT INTO email_classification_daily_budget (budget_date, attempts)
                    VALUES (?, 0)
                    ON CONFLICT (budget_date) DO NOTHING
                    """, sqlDate);
            jdbc.update("""
                    INSERT INTO email_classification_user_daily_budget (budget_date, user_id)
                    VALUES (?, ?)
                    ON CONFLICT (budget_date, user_id) DO NOTHING
                    """, sqlDate, userId);

            BudgetSnapshot global = loadGlobalBudget(sqlDate);
            if (global.attempts() >= properties.getMaxPerDay()) {
                recordGlobalLimitHit(sqlDate);
                return ReservationResult.denied("GLOBAL_ATTEMPT_LIMIT");
            }

            BudgetSnapshot user = loadUserBudget(sqlDate, userId);
            if (user.attempts() >= properties.getMaxPerUserPerDay()) {
                jdbc.update("""
                        UPDATE email_classification_user_daily_budget
                        SET limit_hits = CASE WHEN limit_hits = 0 THEN 1 ELSE limit_hits END
                        WHERE budget_date = ? AND user_id = ?
                        """, sqlDate, userId);
                return ReservationResult.denied("USER_ATTEMPT_LIMIT");
            }

            BigDecimal nextCost = global.estimatedCost().add(reservedCost);
            if (nextCost.compareTo(properties.getMaxCostUsdPerDay()) > 0) {
                recordGlobalLimitHit(sqlDate);
                return ReservationResult.denied("GLOBAL_COST_LIMIT");
            }

            jdbc.update("""
                    UPDATE email_classification_daily_budget
                    SET attempts = attempts + 1,
                        input_tokens = input_tokens + ?,
                        output_tokens = output_tokens + ?,
                        estimated_cost_usd = estimated_cost_usd + ?,
                        limit_hits = CASE
                            WHEN attempts + 1 >= ? AND limit_hits = 0 THEN 1
                            ELSE limit_hits
                        END
                    WHERE budget_date = ?
                    """, reservedInputTokens, reservedOutputTokens, reservedCost,
                    properties.getMaxPerDay(), sqlDate);
            jdbc.update("""
                    UPDATE email_classification_user_daily_budget
                    SET attempts = attempts + 1,
                        input_tokens = input_tokens + ?,
                        output_tokens = output_tokens + ?,
                        estimated_cost_usd = estimated_cost_usd + ?,
                        limit_hits = CASE
                            WHEN attempts + 1 >= ? AND limit_hits = 0 THEN 1
                            ELSE limit_hits
                        END
                    WHERE budget_date = ? AND user_id = ?
                    """, reservedInputTokens, reservedOutputTokens, reservedCost,
                    properties.getMaxPerUserPerDay(), sqlDate, userId);

            Long attemptId = jdbc.queryForObject("""
                    INSERT INTO email_classification_ai_attempt (
                        email_id, user_id, budget_date, attempt_number, queue_type, origin_type,
                        outcome, input_tokens, output_tokens, estimated_cost_usd
                    ) VALUES (?, ?, ?, ?, ?, ?, 'STARTED', ?, ?, ?)
                    RETURNING id
                    """, Long.class, emailId, userId, sqlDate, attemptNumber, queueType, originType,
                    reservedInputTokens, reservedOutputTokens, reservedCost);

            jdbc.update("""
                    UPDATE emails
                    SET classification_attempts = ?,
                        classification_last_attempt_at = ?,
                        classification_active_attempt_id = ?
                    WHERE id = ?
                    """, attemptNumber,
                    OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC), attemptId, emailId);
            return ReservationResult.allowed(new Reservation(
                    attemptId, sqlDate, userId, emailId, attemptNumber,
                    reservedInputTokens, reservedOutputTokens, reservedCost));
        });
    }

    public void complete(
            Reservation reservation,
            int actualInputTokens,
            int actualOutputTokens,
            boolean usageReported,
            boolean successful,
            String failureCategory) {
        int inputTokens = Math.max(0, actualInputTokens);
        int outputTokens = Math.max(0, actualOutputTokens);
        BigDecimal actualCost = estimateCost(inputTokens, outputTokens);
        Date budgetDate = reservation.budgetDate();
        int successDelta = successful ? 1 : 0;
        int failedDelta = successful ? 0 : 1;
        int failureCount = successful ? 0 : 1;

        transactions.executeWithoutResult(status -> {
            List<Long> started = jdbc.query("""
                    UPDATE email_classification_ai_attempt
                    SET outcome = ?, input_tokens = ?, output_tokens = ?,
                        estimated_cost_usd = ?, usage_reported = ?,
                        failure_category = ?, completed_at = ?
                    WHERE id = ? AND outcome = 'STARTED'
                    RETURNING id
                    """,
                    (rs, rowNum) -> rs.getLong(1),
                    successful ? "SUCCEEDED" : "FAILED",
                    inputTokens,
                    outputTokens,
                    actualCost,
                    usageReported,
                    failureCategory,
                    OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC),
                    reservation.attemptId());
            if (started.isEmpty()) {
                return;
            }

            BigDecimal costDelta = actualCost.subtract(reservation.reservedCost());
            int inputDelta = inputTokens - reservation.reservedInputTokens();
            int outputDelta = outputTokens - reservation.reservedOutputTokens();
            jdbc.update("""
                    UPDATE email_classification_daily_budget
                    SET successful_calls = successful_calls + ?,
                        failed_calls = failed_calls + ?,
                        classification_failures = classification_failures + ?,
                        input_tokens = GREATEST(0, input_tokens + ?),
                        output_tokens = GREATEST(0, output_tokens + ?),
                        estimated_cost_usd = GREATEST(0, estimated_cost_usd + ?)
                    WHERE budget_date = ?
                    """, successDelta, failedDelta, failureCount,
                    inputDelta, outputDelta, costDelta, budgetDate);
            jdbc.update("""
                    UPDATE email_classification_user_daily_budget
                    SET successful_calls = successful_calls + ?,
                        failed_calls = failed_calls + ?,
                        classification_failures = classification_failures + ?,
                        input_tokens = GREATEST(0, input_tokens + ?),
                        output_tokens = GREATEST(0, output_tokens + ?),
                        estimated_cost_usd = GREATEST(0, estimated_cost_usd + ?)
                    WHERE budget_date = ? AND user_id = ?
                    """, successDelta, failedDelta, failureCount,
                    inputDelta, outputDelta, costDelta, budgetDate, reservation.userId());
        });
    }

    public void recordPrefilter(Long userId) {
        Date budgetDate = Date.valueOf(LocalDate.now(clock.withZone(budgetZone)));
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO email_classification_daily_budget (budget_date, attempts)
                    VALUES (?, 0)
                    ON CONFLICT (budget_date) DO NOTHING
                    """, budgetDate);
            jdbc.update("""
                    INSERT INTO email_classification_user_daily_budget (budget_date, user_id)
                    VALUES (?, ?)
                    ON CONFLICT (budget_date, user_id) DO NOTHING
                    """, budgetDate, userId);
            jdbc.update("""
                    UPDATE email_classification_daily_budget
                    SET prefiltered_emails = prefiltered_emails + 1
                    WHERE budget_date = ?
                    """, budgetDate);
            jdbc.update("""
                    UPDATE email_classification_user_daily_budget
                    SET prefiltered_emails = prefiltered_emails + 1
                    WHERE budget_date = ? AND user_id = ?
                    """, budgetDate, userId);
        });
    }

    public void markStale(Long attemptId) {
        if (attemptId == null) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            List<StaleAttempt> staleAttempts = jdbc.query("""
                    UPDATE email_classification_ai_attempt
                    SET outcome = 'STALE', failure_category = 'stale_processing',
                        completed_at = ?
                    WHERE id = ? AND outcome = 'STARTED'
                    RETURNING budget_date, user_id
                    """,
                    (rs, rowNum) -> new StaleAttempt(rs.getDate(1), rs.getLong(2)),
                    OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC), attemptId);
            if (staleAttempts.isEmpty()) {
                return;
            }
            StaleAttempt stale = staleAttempts.get(0);
            jdbc.update("""
                    UPDATE email_classification_daily_budget
                    SET failed_calls = failed_calls + 1,
                        classification_failures = classification_failures + 1
                    WHERE budget_date = ?
                    """, stale.budgetDate());
            jdbc.update("""
                    UPDATE email_classification_user_daily_budget
                    SET failed_calls = failed_calls + 1,
                        classification_failures = classification_failures + 1
                    WHERE budget_date = ? AND user_id = ?
                    """, stale.budgetDate(), stale.userId());
        });
    }

    public LocalDate currentBudgetDate() {
        return LocalDate.now(clock.withZone(budgetZone));
    }

    public Instant nextBudgetPeriod() {
        return currentBudgetDate().plusDays(1).atStartOfDay(budgetZone).toInstant();
    }

    public BigDecimal estimateCost(int inputTokens, int outputTokens) {
        BigDecimal inputCost = properties.getInputPriceUsdPerMillionTokens()
                .multiply(BigDecimal.valueOf(Math.max(0, inputTokens)))
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal outputCost = properties.getOutputPriceUsdPerMillionTokens()
                .multiply(BigDecimal.valueOf(Math.max(0, outputTokens)))
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(9, RoundingMode.HALF_UP);
    }

    private BudgetSnapshot loadGlobalBudget(Date budgetDate) {
        return jdbc.queryForObject("""
                SELECT attempts, estimated_cost_usd, limit_hits
                FROM email_classification_daily_budget
                WHERE budget_date = ?
                FOR UPDATE
                """, (rs, rowNum) -> new BudgetSnapshot(
                rs.getInt(1), rs.getBigDecimal(2), rs.getInt(3)), budgetDate);
    }

    private BudgetSnapshot loadUserBudget(Date budgetDate, Long userId) {
        return jdbc.queryForObject("""
                SELECT attempts, estimated_cost_usd, limit_hits
                FROM email_classification_user_daily_budget
                WHERE budget_date = ? AND user_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new BudgetSnapshot(
                rs.getInt(1), rs.getBigDecimal(2), rs.getInt(3)), budgetDate, userId);
    }

    private void recordGlobalLimitHit(Date budgetDate) {
        jdbc.update("""
                UPDATE email_classification_daily_budget
                SET limit_hits = CASE WHEN limit_hits = 0 THEN 1 ELSE limit_hits END
                WHERE budget_date = ?
                """, budgetDate);
    }

    private void validateConfiguration() {
        if (properties.getMaxPerDay() < 1
                || properties.getMaxPerUserPerDay() < 1
                || properties.getMaxCostUsdPerDay() == null
                || properties.getMaxCostUsdPerDay().signum() <= 0
                || properties.getInputPriceUsdPerMillionTokens() == null
                || properties.getInputPriceUsdPerMillionTokens().signum() < 0
                || properties.getOutputPriceUsdPerMillionTokens() == null
                || properties.getOutputPriceUsdPerMillionTokens().signum() < 0
                || properties.getMaxInputTokens() < 1
                || properties.getMaxOutputTokens() < 1
                || properties.getDailyBudgetTimeZone() == null
                || properties.getDailyBudgetTimeZone().isBlank()) {
            throw new IllegalArgumentException("Invalid email classification budget or token configuration");
        }
    }

    public record Reservation(
            Long attemptId,
            Date budgetDate,
            Long userId,
            Long emailId,
            int attemptNumber,
            int reservedInputTokens,
            int reservedOutputTokens,
            BigDecimal reservedCost) {
    }

    public record ReservationResult(Reservation reservation, String denialReason) {
        static ReservationResult allowed(Reservation reservation) {
            return new ReservationResult(reservation, null);
        }

        static ReservationResult denied(String reason) {
            return new ReservationResult(null, reason);
        }

        public boolean allowed() {
            return reservation != null;
        }
    }

    private record BudgetSnapshot(int attempts, BigDecimal estimatedCost, int limitHits) {
    }

    private record StaleAttempt(Date budgetDate, Long userId) {
    }
}
