package com.buildassist.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.buildassist.model.EmailCategory;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import com.buildassist.config.AppProperties;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-only-not-used",
        "spring.security.oauth2.client.registration.google.client-id=test-only-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-only-secret",
        "app.email-classification.enabled=false",
        "app.email-classification.max-per-day=3",
        "app.email-classification.max-per-user-per-day=2",
        "app.email-classification.max-cost-usd-per-day=0.0001",
        "app.email-classification.poll-interval-ms=2147483647"
})
@ActiveProfiles("test")
@Import(EmailClassificationBudgetIntegrationTest.FixedClockConfiguration.class)
class EmailClassificationBudgetIntegrationTest {

    private static final Instant TEST_INSTANT = Instant.parse("2099-01-15T12:00:00Z");
    private static final LocalDate BUDGET_DATE = LocalDate.of(2099, 1, 15);
    private static final String TEST_ENCRYPTION_KEY = UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        registry.add("app.encryption.aes-key", () -> TEST_ENCRYPTION_KEY);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmailClassificationBudgetService budgets;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EmailRepository emails;

    @Autowired
    private GmailConnectionRepository connections;

    @Autowired
    private EmailService emailService;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void cleanBudgetDate() {
        jdbc.update("DELETE FROM email_classification_daily_budget WHERE budget_date = ?", BUDGET_DATE);
        jdbc.update("DELETE FROM email_classification_daily_budget WHERE budget_date = ?", BUDGET_DATE.plusDays(1));
    }

    @AfterEach
    void cleanTestRows() {
        for (Long userId : createdUserIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
        jdbc.update("DELETE FROM email_classification_daily_budget WHERE budget_date = ?", BUDGET_DATE);
        jdbc.update("DELETE FROM email_classification_daily_budget WHERE budget_date = ?", BUDGET_DATE.plusDays(1));
    }

    @Test
    void concurrentReservationsEnforceUserAndGlobalLimitsAtomically() throws Exception {
        TestAccount firstUser = createAccount();
        TestAccount secondUser = createAccount();
        TestAccount thirdUser = createAccount();
        EmailClassificationBudgetService secondInstance =
                new EmailClassificationBudgetService(jdbc, appProperties, clock, transactionManager);
        Long firstEmail = createProcessingEmail(firstUser);
        Long secondEmail = createProcessingEmail(firstUser);
        Long thirdEmail = createProcessingEmail(firstUser);
        Long otherUserEmail = createProcessingEmail(secondUser);
        Long thirdUserEmail = createProcessingEmail(thirdUser);

        var executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<EmailClassificationBudgetService.ReservationResult> first = executor.submit(() -> {
                start.await();
                return reserve(firstEmail, firstUser.id());
            });
            Future<EmailClassificationBudgetService.ReservationResult> second = executor.submit(() -> {
                start.await();
                return reserve(secondEmail, firstUser.id());
            });
            Future<EmailClassificationBudgetService.ReservationResult> third = executor.submit(() -> {
                start.await();
                return reserve(secondInstance, thirdEmail, firstUser.id(), 10);
            });
            start.countDown();

            List<EmailClassificationBudgetService.ReservationResult> results =
                    List.of(first.get(), second.get(), third.get());
            assertEquals(2, results.stream().filter(
                    EmailClassificationBudgetService.ReservationResult::allowed).count());
            assertEquals(1, results.stream().filter(result ->
                    "USER_ATTEMPT_LIMIT".equals(result.denialReason())).count());
        } finally {
            executor.shutdownNow();
        }

        assertTrue(reserve(otherUserEmail, secondUser.id()).allowed());
        assertEquals("GLOBAL_ATTEMPT_LIMIT",
                reserve(thirdUserEmail, thirdUser.id()).denialReason());
        assertEquals(3, jdbc.queryForObject(
                "SELECT attempts FROM email_classification_daily_budget WHERE budget_date = ?",
                Integer.class, BUDGET_DATE));
        assertEquals(2, jdbc.queryForObject("""
                SELECT attempts FROM email_classification_user_daily_budget
                WHERE budget_date = ? AND user_id = ?
                """, Integer.class, BUDGET_DATE, firstUser.id()));
    }

    @Test
    void estimatedCostIsReservedBeforeAnotherCallCanExceedTheDailyCeiling() {
        TestAccount user = createAccount();
        Long firstEmail = createProcessingEmail(user);
        Long secondEmail = createProcessingEmail(user);

        assertTrue(reserve(firstEmail, user.id(), 100).allowed());
        assertEquals("GLOBAL_COST_LIMIT", reserve(secondEmail, user.id(), 100).denialReason());
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM email_classification_daily_budget WHERE budget_date = ?",
                Integer.class, BUDGET_DATE));
    }

    @Test
    void concurrentCostReservationsAcrossInstancesStayWithinTheEstimatedDailyCeiling() throws Exception {
        TestAccount firstUser = createAccount();
        TestAccount secondUser = createAccount();
        Long firstEmail = createProcessingEmail(firstUser);
        Long secondEmail = createProcessingEmail(secondUser);
        EmailClassificationBudgetService secondInstance =
                new EmailClassificationBudgetService(jdbc, appProperties, clock, transactionManager);

        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<EmailClassificationBudgetService.ReservationResult> first = executor.submit(() -> {
                start.await();
                return reserve(budgets, firstEmail, firstUser.id(), 100);
            });
            Future<EmailClassificationBudgetService.ReservationResult> second = executor.submit(() -> {
                start.await();
                return reserve(secondInstance, secondEmail, secondUser.id(), 100);
            });
            start.countDown();

            List<EmailClassificationBudgetService.ReservationResult> results =
                    List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(
                    EmailClassificationBudgetService.ReservationResult::allowed).count());
            assertEquals(1, results.stream().filter(result ->
                    "GLOBAL_COST_LIMIT".equals(result.denialReason())).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM email_classification_daily_budget WHERE budget_date = ?",
                Integer.class, BUDGET_DATE));
    }

    @Test
    void actualUsageReconcilesTheReservedEstimateInTheDatabase() {
        TestAccount user = createAccount();
        Long emailId = createProcessingEmail(user);
        EmailClassificationBudgetService.Reservation reservation =
                reserve(emailId, user.id(), 100).reservation();

        budgets.complete(reservation, 120, 12, true, true, null);

        assertEquals(1, jdbc.queryForObject(
                "SELECT successful_calls FROM email_classification_daily_budget WHERE budget_date = ?",
                Integer.class, BUDGET_DATE));
        assertEquals(120L, jdbc.queryForObject(
                "SELECT input_tokens FROM email_classification_daily_budget WHERE budget_date = ?",
                Long.class, BUDGET_DATE));
        assertEquals(12L, jdbc.queryForObject(
                "SELECT output_tokens FROM email_classification_daily_budget WHERE budget_date = ?",
                Long.class, BUDGET_DATE));
        assertEquals(0, new java.math.BigDecimal("0.000025200").compareTo(jdbc.queryForObject(
                "SELECT estimated_cost_usd FROM email_classification_daily_budget WHERE budget_date = ?",
                java.math.BigDecimal.class, BUDGET_DATE)));
        assertTrue(jdbc.queryForObject(
                "SELECT usage_reported FROM email_classification_ai_attempt WHERE id = ?",
                Boolean.class, reservation.attemptId()));

        Long failedEmailId = createProcessingEmail(user);
        EmailClassificationBudgetService.Reservation failedReservation =
                reserve(failedEmailId, user.id(), 50).reservation();
        budgets.complete(
                failedReservation,
                failedReservation.reservedInputTokens(),
                failedReservation.reservedOutputTokens(),
                false,
                false,
                "provider_interrupted");

        assertEquals(1, jdbc.queryForObject(
                "SELECT failed_calls FROM email_classification_daily_budget WHERE budget_date = ?",
                Integer.class, BUDGET_DATE));
        assertEquals(1, jdbc.queryForObject(
                "SELECT classification_failures FROM email_classification_daily_budget WHERE budget_date = ?",
                Integer.class, BUDGET_DATE));
        assertTrue(jdbc.queryForObject(
                "SELECT NOT usage_reported FROM email_classification_ai_attempt WHERE id = ?",
                Boolean.class, failedReservation.attemptId()));
    }

    @Test
    void aNewUtcBudgetPeriodAllowsProcessingAfterTheGlobalLimitWasReached() {
        TestAccount firstUser = createAccount();
        TestAccount secondUser = createAccount();
        Long firstEmail = createProcessingEmail(firstUser);
        Long secondEmail = createProcessingEmail(firstUser);
        Long thirdEmail = createProcessingEmail(secondUser);
        Long nextDayEmail = createProcessingEmail(secondUser);

        assertTrue(reserve(firstEmail, firstUser.id()).allowed());
        assertTrue(reserve(secondEmail, firstUser.id()).allowed());
        assertTrue(reserve(thirdEmail, secondUser.id()).allowed());
        assertEquals("GLOBAL_ATTEMPT_LIMIT", reserve(nextDayEmail, secondUser.id()).denialReason());

        EmailClassificationBudgetService nextDayBudgets = new EmailClassificationBudgetService(
                jdbc,
                appProperties,
                Clock.fixed(TEST_INSTANT.plusSeconds(86_400), ZoneOffset.UTC),
                transactionManager);
        assertTrue(reserve(nextDayBudgets, nextDayEmail, secondUser.id(), 10).allowed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM email_classification_daily_budget WHERE budget_date = ?",
                Integer.class, Date.valueOf(BUDGET_DATE.plusDays(1))));
    }

    @Test
    void databaseQueuePrefersFreshEmailsAndRotatesBetweenUsers() {
        TestAccount firstUser = createAccount();
        TestAccount secondUser = createAccount();
        createQueuedEmail(firstUser, "HISTORICAL", TEST_INSTANT.minusSeconds(86_400L * 30));
        createQueuedEmail(firstUser, "FRESH", TEST_INSTANT);
        createQueuedEmail(secondUser, "FRESH", TEST_INSTANT);

        var transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        Long firstClaimId = transactions.execute(status -> lockNextDueEmailId());
        assertEquals("FRESH", jdbc.queryForObject(
                "SELECT classification_queue_type FROM emails WHERE id = ?",
                String.class, firstClaimId));
        Long firstClaimedUserId = jdbc.queryForObject("""
                SELECT gc.user_id FROM emails e
                JOIN gmail_connections gc ON gc.id = e.gmail_connection_id
                WHERE e.id = ?
                """, Long.class, firstClaimId);
        transactions.executeWithoutResult(status ->
                emails.touchUserClassificationClaim(Date.valueOf(BUDGET_DATE), firstClaimedUserId));

        Long secondClaimId = transactions.execute(status -> lockNextDueEmailId());
        Long secondClaimedUserId = jdbc.queryForObject("""
                SELECT gc.user_id FROM emails e
                JOIN gmail_connections gc ON gc.id = e.gmail_connection_id
                WHERE e.id = ?
                """, Long.class, secondClaimId);

        assertNotEquals(firstClaimedUserId, secondClaimedUserId);
    }

    @Test
    void concurrentDatabaseClaimsSkipAnEmailAlreadyLockedByAnotherInstance() throws Exception {
        TestAccount user = createAccount();
        createQueuedEmail(user, "FRESH", TEST_INSTANT);
        createQueuedEmail(user, "FRESH", TEST_INSTANT.plusSeconds(1));

        var transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstClaimLocked = new CountDownLatch(1);
        CountDownLatch releaseFirstClaim = new CountDownLatch(1);
        try {
            Future<Long> firstClaim = executor.submit(() -> transactions.execute(status -> {
                Long emailId = lockNextDueEmailId();
                firstClaimLocked.countDown();
                try {
                    if (!releaseFirstClaim.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the first email claim");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the first email claim", ex);
                }
                return emailId;
            }));
            assertTrue(firstClaimLocked.await(10, java.util.concurrent.TimeUnit.SECONDS));
            Long secondClaim = transactions.execute(status -> lockNextDueEmailId());
            releaseFirstClaim.countDown();

            assertNotEquals(firstClaim.get(), secondClaim);
        } finally {
            releaseFirstClaim.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void manualCategoryUpdateDuringARealDatabaseBackedAiCallWins() {
        TestAccount user = createAccount();
        Long emailId = createQueuedEmail(user, "FRESH", TEST_INSTANT);
        EmailClassificationModel model = context -> {
            emailService.updateCategory(user.id(), emailId, EmailCategory.OTHER);
            return new EmailClassificationModel.ClassificationResponse("""
                    {"category":"QUOTE_REQUEST","reason":"Új munkát kér.","evidence":"ajánlatot"}
                    """, 100, 20);
        };
        EmailClassificationService classifier = new EmailClassificationService(
                emails,
                connections,
                model,
                budgets,
                appProperties,
                new ObjectMapper(),
                clock,
                jdbc,
                Runnable::run,
                transactionManager);
        boolean classificationEnabled = appProperties.getEmailClassification().isEnabled();
        java.math.BigDecimal maxCostUsdPerDay = appProperties.getEmailClassification().getMaxCostUsdPerDay();
        try {
            appProperties.getEmailClassification().setEnabled(true);
            appProperties.getEmailClassification().setMaxCostUsdPerDay(new java.math.BigDecimal("10.00"));
            classifier.classifyDueEmails();
        } finally {
            appProperties.getEmailClassification().setEnabled(classificationEnabled);
            appProperties.getEmailClassification().setMaxCostUsdPerDay(maxCostUsdPerDay);
        }

        assertEquals("OTHER", jdbc.queryForObject(
                "SELECT category FROM emails WHERE id = ?", String.class, emailId));
        assertEquals("MANUAL", jdbc.queryForObject(
                "SELECT classification_source FROM emails WHERE id = ?", String.class, emailId));
        assertEquals("CLASSIFIED", jdbc.queryForObject(
                "SELECT classification_status FROM emails WHERE id = ?", String.class, emailId));
    }

    private Long lockNextDueEmailId() {
        return emails.lockNextClassificationDue(
                        clock.instant(),
                        clock.instant().minusSeconds(600),
                        Date.valueOf(BUDGET_DATE),
                        appProperties.getEmailClassification().getMaxPerUserPerDay(),
                        clock.instant().minusSeconds(120 * 60L))
                .orElseThrow()
                .getId();
    }

    private Long createQueuedEmail(TestAccount account, String queueType, Instant queuedAt) {
        return jdbc.queryForObject("""
                INSERT INTO emails (
                    gmail_connection_id, gmail_message_id, subject, from_address, body_text, email_received_at,
                    category, classification_status, classification_source,
                    classification_queue_type, classification_origin, classification_queued_at
                ) VALUES (?, ?, 'New work', 'customer@example.com', 'Please provide a quote.', ?,
                          'UNCLASSIFIED', 'PENDING', 'PENDING', ?, ?, ?)
                RETURNING id
                """, Long.class, account.connectionId(), UUID.randomUUID().toString(),
                OffsetDateTime.ofInstant(queuedAt, ZoneOffset.UTC),
                queueType, queueType, OffsetDateTime.ofInstant(queuedAt, ZoneOffset.UTC));
    }

    private EmailClassificationBudgetService.ReservationResult reserve(
            EmailClassificationBudgetService service,
            Long emailId,
            Long userId,
            int maxOutputTokens) {
        return service.reserve(emailId, userId, "FRESH", "FRESH", 1, 100, maxOutputTokens);
    }

    private EmailClassificationBudgetService.ReservationResult reserve(Long emailId, Long userId) {
        return reserve(emailId, userId, 10);
    }

    private EmailClassificationBudgetService.ReservationResult reserve(
            Long emailId,
            Long userId,
            int maxOutputTokens) {
        return reserve(budgets, emailId, userId, maxOutputTokens);
    }

    private TestAccount createAccount() {
        String email = "classification-" + UUID.randomUUID() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, is_active)
                VALUES (?, 'test-only', TRUE)
                RETURNING id
                """, Long.class, email);
        createdUserIds.add(userId);
        Long connectionId = jdbc.queryForObject("""
                INSERT INTO gmail_connections (
                    user_id, gmail_address, access_token_encrypted, refresh_token_encrypted, token_expires_at
                ) VALUES (?, ?, 'test-only', 'test-only', ?)
                RETURNING id
                """, Long.class, userId, email,
                OffsetDateTime.ofInstant(TEST_INSTANT.plusSeconds(3_600), ZoneOffset.UTC));
        return new TestAccount(userId, connectionId);
    }

    private Long createProcessingEmail(TestAccount account) {
        return jdbc.queryForObject("""
                INSERT INTO emails (
                    gmail_connection_id, gmail_message_id, subject, body_text, email_received_at,
                    category, classification_status, classification_source,
                    classification_queue_type, classification_origin,
                    classification_queued_at, classification_started_at
                ) VALUES (?, ?, 'New work', 'Please provide a quote.', ?, 'UNCLASSIFIED',
                          'PROCESSING', 'PENDING', 'FRESH', 'FRESH', ?, ?)
                RETURNING id
                """, Long.class, account.connectionId(), UUID.randomUUID().toString(),
                OffsetDateTime.ofInstant(TEST_INSTANT, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(TEST_INSTANT, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(TEST_INSTANT, ZoneOffset.UTC));
    }

    private record TestAccount(Long id, Long connectionId) {
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock classificationTestClock() {
            return Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
        }
    }
}
