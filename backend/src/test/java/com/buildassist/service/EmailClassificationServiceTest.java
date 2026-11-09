package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailClassificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T14:15:16Z");

    @Mock
    private EmailRepository emails;
    @Mock
    private GmailConnectionRepository connections;
    @Mock
    private EmailClassificationModel model;
    @Mock
    private EmailClassificationBudgetService budgets;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    private EmailClassificationService service;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        appProperties = new AppProperties();
        appProperties.getEmailClassification().setEnabled(true);
        appProperties.getEmailClassification().setMaxPerRun(1);
        appProperties.getEmailClassification().setRetryBaseSeconds(60);
        appProperties.getEmailClassification().setRetryMaxSeconds(86_400);
        appProperties.getEmailClassification().setMaxAttempts(3);
        appProperties.getEmailClassification().setVersion("v22");
        appProperties.getEmailClassification().setMaxPerUserPerDay(150);
        appProperties.getEmailClassification().setMaxInputTokens(4_000);
        appProperties.getEmailClassification().setMaxOutputTokens(256);
        appProperties.getEmailClassification().setMetricsIntervalMs(300_000);
        TaskExecutor directExecutor = Runnable::run;
        lenient().when(budgets.currentBudgetDate()).thenReturn(LocalDate.now(Clock.fixed(NOW, ZoneOffset.UTC)));
        service = new EmailClassificationService(
                emails,
                connections,
                model,
                budgets,
                appProperties,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                jdbcTemplate,
                directExecutor,
                transactionManager);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("representativeHungarianEmails")
    void appliesStructuredAiDecisionsAcrossHungarianEmailCases(
            EmailFixture fixture) throws Exception {
        Email email = queuedEmail(fixture.emailText());
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(new EmailClassificationModel.ClassificationResponse("""
                {"category":"%s","reason":"A legfrissebb levél tartalma ezt támasztja alá.",
                 "evidence":"%s"}
                """.formatted(fixture.mockPrediction(), fixture.description()), null, null));

        service.classifyDueEmails();

        assertEquals(fixture.mockPrediction(), email.getCategory(), fixture.description());
        assertEquals("AI", email.getClassificationSource());
        assertEquals("v22", email.getClassificationVersion());
        assertEquals(fixture.mockPrediction() == EmailCategory.CLASSIFICATION_REVIEW ? "REVIEW" : "CLASSIFIED",
                email.getClassificationStatus());
    }

    @Test
    void schedulerRunClaimsNoMoreThanTheConfiguredMaximum() {
        appProperties.getEmailClassification().setMaxPerRun(5);
        List<Email> batch = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> {
                    Email email = queuedEmail("Új tetőfedési munkára kérek ajánlatot.");
                    email.setId(101L + index);
                    return email;
                })
                .toList();
        Map<Long, Email> emailsById = new HashMap<>();
        batch.forEach(email -> emailsById.put(email.getId(), email));
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(batch.get(0)))
                .thenReturn(Optional.of(batch.get(1)))
                .thenReturn(Optional.of(batch.get(2)))
                .thenReturn(Optional.of(batch.get(3)))
                .thenReturn(Optional.of(batch.get(4)))
                .thenReturn(Optional.of(batch.get(5)));
        when(emails.findByIdForClassificationUpdate(any()))
                .thenAnswer(invocation -> Optional.of(emailsById.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            Long emailId = invocation.getArgument(0);
            Email email = emailsById.get(emailId);
            int attemptNumber = invocation.getArgument(4);
            EmailClassificationBudgetService.Reservation reservation =
                    new EmailClassificationBudgetService.Reservation(
                            emailId + 1_000,
                            Date.valueOf(LocalDate.now(Clock.fixed(NOW, ZoneOffset.UTC))),
                            1L,
                            emailId,
                            attemptNumber,
                            100,
                            256,
                            new BigDecimal("0.000200000"));
            email.setClassificationAttempts(attemptNumber);
            email.setClassificationActiveAttemptId(reservation.attemptId());
            return EmailClassificationBudgetService.ReservationResult.allowed(reservation);
        }).when(budgets).reserve(any(), any(), anyString(), anyString(),
                any(Integer.class), any(Integer.class), any(Integer.class));
        when(model.classify(anyString())).thenReturn(response(EmailCategory.OTHER));

        service.classifyDueEmails();

        verify(emails, org.mockito.Mockito.times(5)).lockNextClassificationDue(
                any(), any(), any(), any(Integer.class), any());
        verify(model, org.mockito.Mockito.times(5)).classify(anyString());
        assertEquals("PENDING", batch.get(5).getClassificationStatus());
    }

    @Test
    void mockedHungarianGoldenSetReportsAccuracyAndQuotePrecisionRecall() {
        int correct = 0;
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        for (EmailFixture fixture : emailFixtures()) {
            if (fixture.expected() == fixture.mockPrediction()) {
                correct++;
            }
            if (fixture.expected() == EmailCategory.QUOTE_REQUEST
                    && fixture.mockPrediction() == EmailCategory.QUOTE_REQUEST) {
                truePositives++;
            } else if (fixture.expected() != EmailCategory.QUOTE_REQUEST
                    && fixture.mockPrediction() == EmailCategory.QUOTE_REQUEST) {
                falsePositives++;
            } else if (fixture.expected() == EmailCategory.QUOTE_REQUEST
                    && fixture.mockPrediction() != EmailCategory.QUOTE_REQUEST) {
                falseNegatives++;
            }
        }
        double accuracy = (double) correct / emailFixtures().size();
        double quotePrecision = (double) truePositives / (truePositives + falsePositives);
        double quoteRecall = (double) truePositives / (truePositives + falseNegatives);

        assertEquals(41, emailFixtures().size());
        org.junit.jupiter.api.Assertions.assertTrue(accuracy >= 0.90, "mocked accuracy=" + accuracy);
        org.junit.jupiter.api.Assertions.assertTrue(quotePrecision >= 0.90, "quote precision=" + quotePrecision);
        org.junit.jupiter.api.Assertions.assertTrue(quoteRecall >= 0.90, "quote recall=" + quoteRecall);
    }

    @Test
    void noreplySenderWithRealQuoteRequestStillUsesAiClassification() {
        Email email = queuedEmail("A tető felújítására szeretnék árajánlatot kérni.");
        email.setFromAddress("no-reply@example.hu");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(response(EmailCategory.QUOTE_REQUEST));

        service.classifyDueEmails();

        verify(model).classify(anyString());
        assertEquals(EmailCategory.QUOTE_REQUEST, email.getCategory());
    }

    @Test
    void webContactFormMessageStillUsesAiClassification() {
        Email email = queuedEmail("Családi házunk tetőfedésére kérnénk árajánlatot.");
        email.setFromAddress("noreply@kapcsolat.example.hu");
        email.setClassificationMetadata("{\"Content-Type\":\"text/plain; charset=UTF-8\"}");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(response(EmailCategory.QUOTE_REQUEST));

        service.classifyDueEmails();

        verify(model).classify(anyString());
        assertEquals(EmailCategory.QUOTE_REQUEST, email.getCategory());
    }

    @Test
    void emptyEmailSkipsModelAndRequiresManualClassification() {
        Email email = queuedEmail("");
        email.setSubject(null);
        email.setFromAddress(null);
        email.setClassificationMetadata("{}");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));

        service.classifyDueEmails();

        assertEquals(EmailCategory.CLASSIFICATION_REVIEW, email.getCategory());
        assertEquals("REVIEW", email.getClassificationStatus());
        verify(model, never()).classify(anyString());
    }

    @Test
    void modelFailureSchedulesRetryWithoutClassifyingAsQuoteRequest() {
        Email email = queuedEmail("Szeretnék ajánlatot kérni új tetőfedési munkára.");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenThrow(new IllegalStateException("provider unavailable"));

        service.classifyDueEmails();

        assertEquals(EmailCategory.OTHER, email.getCategory());
        assertEquals("RETRY", email.getClassificationStatus());
        assertEquals(NOW.plusSeconds(60), email.getClassificationRetryAt());
        assertEquals(1, email.getClassificationAttempts());
    }

    @Test
    void exhaustedRetriesMoveToReviewInsteadOfInventingAQuoteRequest() {
        Email email = queuedEmail("Kérem küldjön ajánlatot.");
        email.setClassificationAttempts(2);
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenThrow(new IllegalArgumentException("malformed response"));

        service.classifyDueEmails();

        assertEquals(EmailCategory.CLASSIFICATION_REVIEW, email.getCategory());
        assertEquals("FAILED", email.getClassificationStatus());
        assertNotNull(email.getClassificationReason());
        assertEquals(null, email.getClassificationRetryAt());
    }

    @Test
    void manualCategoryChangeDuringAiCallIsNeverOverwritten() {
        Email email = queuedEmail("Új munkára kérek ajánlatot.");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenAnswer(invocation -> {
            email.setCategory(EmailCategory.OTHER);
            email.setClassificationSource("MANUAL");
            email.setClassificationStatus("CLASSIFIED");
            return response(EmailCategory.QUOTE_REQUEST);
        });

        service.classifyDueEmails();

        assertEquals(EmailCategory.OTHER, email.getCategory());
        assertEquals("MANUAL", email.getClassificationSource());
    }

    @Test
    void sendsBodyAndAllowlistedMetadataAsModelContext() throws Exception {
        Email email = queuedEmail("Új ereszcsatorna felszerelésére kérek árajánlatot.");
        email.setClassificationMetadata("{\"List-ID\":\"customers\"}");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(response(EmailCategory.QUOTE_REQUEST));

        service.classifyDueEmails();

        org.mockito.ArgumentCaptor<String> context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model).classify(context.capture());
        org.junit.jupiter.api.Assertions.assertTrue(context.getValue().contains("kérek árajánlatot"));
        org.junit.jupiter.api.Assertions.assertTrue(context.getValue().contains("customers"));
    }

    @Test
    void genuineQuoteRequestFromNoReplyAddressStillUsesAi() {
        Email email = queuedEmail("A tető javítására szeretnék árajánlatot kérni.");
        email.setFromAddress("no-reply@customer.example");
        email.setClassificationMetadata("{}");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(response(EmailCategory.QUOTE_REQUEST));

        service.classifyDueEmails();

        verify(model).classify(anyString());
        assertEquals(EmailCategory.QUOTE_REQUEST, email.getCategory());
    }

    @Test
    void websiteContactFormQuoteRequestStillUsesAi() {
        Email email = queuedEmail("A weboldalon keresztül új kerítés építésére kérek ajánlatot.");
        email.setFromAddress("forms@example.test");
        email.setSubject("Website contact form");
        email.setClassificationMetadata("{\"Content-Type\":\"text/plain\"}");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(response(EmailCategory.QUOTE_REQUEST));

        service.classifyDueEmails();

        verify(model).classify(anyString());
        assertEquals(EmailCategory.QUOTE_REQUEST, email.getCategory());
    }

    @Test
    void newsletterWithMultipleAutomatedHeadersSkipsAi() {
        Email email = queuedEmail("Havi kedvezményes ajánlataink.");
        email.setClassificationMetadata("""
                {"List-Id":"monthly offers","List-Unsubscribe":"<mailto:unsubscribe@example.test>",
                 "Precedence":"bulk"}
                """);
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));

        service.classifyDueEmails();

        verify(model, never()).classify(anyString());
        assertEquals(EmailCategory.OTHER, email.getCategory());
        assertEquals("NEWSLETTER_HEADERS_V1", email.getClassificationRuleId());
        assertEquals("RULE", email.getClassificationSource());
    }

    @Test
    void invoiceAndNewWorkRequestTogetherStillUseAi() {
        Email email = queuedEmail(
                "Csatoltam az előző munkáról szóló számlát. Emellett új garázs építésére is kérek ajánlatot.");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(response(EmailCategory.QUOTE_REQUEST));

        service.classifyDueEmails();

        verify(model).classify(anyString());
        assertEquals(EmailCategory.QUOTE_REQUEST, email.getCategory());
    }

    @Test
    void exhaustedBudgetDefersEmailWithoutConsumingRetryAttempt() {
        Email email = queuedEmail("Új munkára kérek ajánlatot.");
        Instant nextPeriod = NOW.plusSeconds(86_400);
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        when(budgets.reserve(any(), any(), anyString(), anyString(),
                any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(EmailClassificationBudgetService.ReservationResult.denied("GLOBAL_ATTEMPT_LIMIT"));
        when(budgets.nextBudgetPeriod()).thenReturn(nextPeriod);

        service.classifyDueEmails();

        assertEquals("PENDING", email.getClassificationStatus());
        assertEquals(nextPeriod, email.getClassificationRetryAt());
        assertEquals(0, email.getClassificationAttempts());
        verify(model, never()).classify(anyString());
    }

    @Test
    void staleProcessingClaimDefersAsRetryInsteadOfStayingStuckProcessing() {
        Email email = queuedEmail("Új munkára kérek ajánlatot.");
        email.setClassificationStatus("PROCESSING");
        email.setClassificationAttempts(1);
        email.setClassificationActiveAttemptId(500L);
        email.setClassificationStartedAt(NOW.minusSeconds(1_200));
        Instant nextPeriod = NOW.plusSeconds(86_400);
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        when(budgets.reserve(any(), any(), anyString(), anyString(),
                any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(EmailClassificationBudgetService.ReservationResult.denied("GLOBAL_ATTEMPT_LIMIT"));
        when(budgets.nextBudgetPeriod()).thenReturn(nextPeriod);

        service.classifyDueEmails();

        verify(budgets).markStale(500L);
        assertEquals("RETRY", email.getClassificationStatus());
        assertEquals("RETRY", email.getClassificationQueueType());
        assertEquals(nextPeriod, email.getClassificationRetryAt());
        assertEquals(1, email.getClassificationAttempts());
    }

    @Test
    void recoveredStaleTaskAtRetryLimitMovesToReviewWithoutAnotherAiCall() {
        Email email = queuedEmail("Új munkára kérek ajánlatot.");
        email.setClassificationStatus("PROCESSING");
        email.setClassificationAttempts(3);
        email.setClassificationActiveAttemptId(500L);
        email.setClassificationStartedAt(NOW.minusSeconds(1_200));
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));

        service.classifyDueEmails();

        verify(budgets).markStale(500L);
        verify(budgets, never()).reserve(
                any(), any(), anyString(), anyString(),
                any(Integer.class), any(Integer.class), any(Integer.class));
        verify(model, never()).classify(anyString());
        assertEquals(EmailCategory.CLASSIFICATION_REVIEW, email.getCategory());
        assertEquals("REVIEW", email.getClassificationStatus());
        assertEquals("ATTEMPTS_EXHAUSTED_REVIEW_V1", email.getClassificationRuleId());
        assertEquals(3, email.getClassificationAttempts());
    }

    @Test
    void modelInputStaysWithinConfiguredTokenLimit() {
        appProperties.getEmailClassification().setMaxInputTokens(512);
        Email email = queuedEmail("Új tetőfedési munkára kérek ajánlatot.\n"
                + "Régi részletek, amelyek nem férnek bele. ".repeat(1_000));
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(response(EmailCategory.QUOTE_REQUEST));

        service.classifyDueEmails();

        org.mockito.ArgumentCaptor<String> context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model).classify(context.capture());
        assertTrue(OpenAiTokenCounter.count(
                SpringAiEmailClassificationModel.EMAIL_CLASSIFY_PROMPT + "\n" + context.getValue()) + 8 <= 512);
        assertTrue(context.getValue().contains("Új tetőfedési munkára"));
    }

    @Test
    void reportedModelUsageIsRecordedWithActualTokenCounts() {
        Email email = queuedEmail("Új munkára kérek ajánlatot.");
        when(emails.lockNextClassificationDue(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(Optional.of(email));
        when(emails.findByIdForClassificationUpdate(101L)).thenReturn(Optional.of(email));
        allowBudget(email);
        when(model.classify(anyString())).thenReturn(new EmailClassificationModel.ClassificationResponse(
                """
                        {"category":"QUOTE_REQUEST","reason":"Új munkát kér.","evidence":"kérek ajánlatot"}
                        """,
                713,
                29));

        service.classifyDueEmails();

        verify(budgets).complete(
                any(EmailClassificationBudgetService.Reservation.class),
                eq(713),
                eq(29),
                eq(true),
                eq(true),
                eq(null));
    }

    private void allowBudget(Email email) {
        EmailClassificationBudgetService.Reservation reservation =
                new EmailClassificationBudgetService.Reservation(
                        501L,
                        Date.valueOf(LocalDate.now(Clock.fixed(NOW, ZoneOffset.UTC))),
                        1L,
                        email.getId(),
                        email.getClassificationAttempts() + 1,
                        100,
                        256,
                        new BigDecimal("0.000200000"));
        doAnswer(invocation -> {
            email.setClassificationAttempts(reservation.attemptNumber());
            email.setClassificationActiveAttemptId(reservation.attemptId());
            return EmailClassificationBudgetService.ReservationResult.allowed(reservation);
        }).when(budgets).reserve(eq(email.getId()), eq(1L), anyString(), anyString(),
                any(Integer.class), any(Integer.class), any(Integer.class));
    }

    private static Email queuedEmail(String body) {
        Email email = new Email();
        User user = new User();
        user.setId(1L);
        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        email.setId(101L);
        email.setGmailConnection(connection);
        email.setSubject("Kivitelezési munka");
        email.setFromAddress("ugyfel@example.hu");
        email.setBodyText(body);
        email.setClassificationStatus("PENDING");
        email.setClassificationSource("PENDING");
        email.setClassificationRetryAt(NOW);
        email.setClassificationAttempts(0);
        email.setClassificationQueueType("HISTORICAL");
        return email;
    }

    private static EmailClassificationModel.ClassificationResponse response(EmailCategory category) {
        return new EmailClassificationModel.ClassificationResponse(
                """
                        {"category":"%s","reason":"Új munkát kér.","evidence":"kérek árajánlatot"}
                        """.formatted(category), null, null);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> representativeHungarianEmails() {
        return emailFixtures().stream().map(fixture ->
                org.junit.jupiter.params.provider.Arguments.of(fixture));
    }

    private static List<EmailFixture> emailFixtures() {
        return List.of(
                example("Tetőfedés új munkára", EmailCategory.QUOTE_REQUEST,
                        "A családi házunk tetőfedésére szeretnék árajánlatot kérni."),
                example("Villanyszerelés új munkára", EmailCategory.QUOTE_REQUEST,
                        "Kérem, adjanak árat a teljes elektromos hálózat kiépítésére."),
                example("Vízvezeték felmérés", EmailCategory.QUOTE_REQUEST,
                        "Szeretnék helyszíni felmérést kérni fürdőszoba felújításához."),
                example("Festés új munkára", EmailCategory.QUOTE_REQUEST,
                        "Két szoba festésére keresek kivitelezőt, tudnak ajánlatot adni?"),
                example("Burkolás új munkára", EmailCategory.QUOTE_REQUEST,
                        "A terasz burkolásának munkadíjáról kérnék árajánlatot."),
                example("Hőszigetelés új munkára", EmailCategory.QUOTE_REQUEST,
                        "A homlokzat hőszigetelését szeretnénk megrendelni, kérem az ajánlatukat."),
                example("Asztalosmunka új munkára", EmailCategory.QUOTE_REQUEST,
                        "Egyedi konyhabútor készítésére kérnék felmérést és árajánlatot."),
                example("Kertépítés új munkára", EmailCategory.QUOTE_REQUEST,
                        "A kertünk térkövezésére vállalnak munkát? Kérek hozzá árat."),
                example("Klímaszerelés új munkára", EmailCategory.QUOTE_REQUEST,
                        "Három klíma felszerelésére kérek ajánlatot a címünkre."),
                example("Ablakcsere új munkára", EmailCategory.QUOTE_REQUEST,
                        "Öt ablak cseréjéhez szeretnék helyszíni felmérést egyeztetni."),
                example("Árajánlat értesítés", EmailCategory.OTHER, EmailCategory.QUOTE_REQUEST,
                        "Értesítjük, hogy az Ön által kért árajánlat elkészült és letölthető."),
                example("Ajánlat elfogadása", EmailCategory.OTHER,
                        "Köszönöm az ajánlatot, elfogadom. Jövő hétfőn kezdhetünk."),
                example("Meglévő munka egyeztetése", EmailCategory.OTHER,
                        "A tegnapi javítás kapcsán pénteken tudnának visszajönni?"),
                example("Számla", EmailCategory.OTHER,
                        "Csatoltuk a múlt havi munkáról kiállított számlát. Fizetési határidő: 8 nap."),
                example("Hírlevél", EmailCategory.OTHER,
                        "Havi akciónkban most kedvezményes áron kaphatók a szerszámok."),
                example("Reklám", EmailCategory.OTHER,
                        "Próbálja ki új építőanyag-katalógusunkat, kérjen ingyenes bemutatót."),
                example("Automatikus értesítés", EmailCategory.OTHER,
                        "Automatikus üzenet: a jelszava sikeresen megváltozott."),
                example("Szállítási visszaigazolás", EmailCategory.OTHER,
                        "Rendelését átadtuk a futárnak, várható kézbesítés holnap."),
                example("Általános ügyintézés", EmailCategory.OTHER,
                        "Kérjük, küldje el újra a szerződésen szereplő adószámot."),
                example("Továbbított ajánlatkérés új kérés nélkül", EmailCategory.OTHER,
                        "Továbbítom az alábbi levelet tájékoztatásul. [Idézett: kérek árajánlatot.]"),
                example("Régi idézett ajánlatkérés", EmailCategory.OTHER,
                        "A korábbi levelemben kértem árat, ezt most csak az előzmény miatt idézem."),
                example("Ajánlat elfogadása tárgyban árral", EmailCategory.OTHER,
                        "Az Ön árkalkulációját elfogadjuk, kérjük kezdje el a már egyeztetett munkát."),
                example("Meglévő projekt státusza", EmailCategory.OTHER,
                        "A már megkezdett felújítás következő üteméről érdeklődöm."),
                example("Meglévő munka számlázása", EmailCategory.OTHER,
                        "A kész munkához tartozó számlát mikor küldik?"),
                example("Rövid kétértelmű kérdés", EmailCategory.CLASSIFICATION_REVIEW,
                        "Mennyibe kerülne?"),
                example("Rövid reakció", EmailCategory.CLASSIFICATION_REVIEW,
                        "Rendben, köszönöm."),
                example("Üres üzenet", EmailCategory.CLASSIFICATION_REVIEW, ""),
                example("Hiányos igény", EmailCategory.CLASSIFICATION_REVIEW,
                        "Lehetne jövő héten?"),
                example("Kétértelmű ajánlat szó", EmailCategory.CLASSIFICATION_REVIEW,
                        "Ajánlat?"),
                example("Bizonytalan továbbítás", EmailCategory.CLASSIFICATION_REVIEW,
                        "Fwd: Nézd meg ezt."),
                example("Beszállítói árajánlat", EmailCategory.OTHER,
                        "Az Ön által rendelt csempe aktuális ára és árajánlata csatolva."),
                example("Árlista értesítés", EmailCategory.OTHER,
                        "Frissített árlistánk megtalálható a weboldalunkon."),
                example("Szakmai rendezvény reklám", EmailCategory.OTHER,
                        "Regisztráljon építőipari konferenciánkra, early bird áron."),
                example("Banki fizetési értesítés", EmailCategory.OTHER,
                        "A tranzakció sikeres volt, az összeg jóváírásra került."),
                example("Garanciális ügyintézés", EmailCategory.OTHER,
                        "A korábbi javításra vonatkozó garanciajegyet csatoltuk."),
                example("Ütemezés meglévő ügyben", EmailCategory.OTHER,
                        "A megbeszélt munkát kedden kezdjük, kérem jelezzen vissza."),
                example("Új kerítésépítési kérés", EmailCategory.QUOTE_REQUEST,
                        "Új kerítést szeretnénk építtetni, kérnénk helyszíni felmérést és árat."),
                example("Új vakolási munka", EmailCategory.QUOTE_REQUEST, EmailCategory.OTHER,
                        "A mellékelt ház vakolására keresünk vállalkozót, adna ajánlatot?"),
                example("Új fűtéskorszerűsítés", EmailCategory.QUOTE_REQUEST,
                        "A fűtési rendszer korszerűsítésére szeretnék árajánlatot kérni."),
                example("Új bontási munka", EmailCategory.QUOTE_REQUEST,
                        "Egy válaszfal bontására és elszállítására kérek munkadíjat."),
                example("Új burkolati felmérés", EmailCategory.QUOTE_REQUEST,
                        "Kérem mérjék fel az üzlethelyiség padlóját és küldjenek árajánlatot."));
    }

    private static EmailFixture example(
            String description, EmailCategory expected, String text) {
        return new EmailFixture(description, expected, expected, text);
    }

    private static EmailFixture example(
            String description, EmailCategory expected, EmailCategory mockPrediction, String text) {
        return new EmailFixture(description, expected, mockPrediction, text);
    }

    record EmailFixture(
            String description,
            EmailCategory expected,
            EmailCategory mockPrediction,
            String emailText) {
        @Override
        public String toString() {
            return description;
        }
    }
}
