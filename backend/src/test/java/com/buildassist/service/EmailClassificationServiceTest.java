package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    private EmailClassificationService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        AppProperties properties = new AppProperties();
        properties.getEmailClassification().setEnabled(true);
        properties.getEmailClassification().setMaxPerRun(1);
        properties.getEmailClassification().setRetryBaseSeconds(60);
        properties.getEmailClassification().setMaxAttempts(3);
        properties.getEmailClassification().setVersion("v22");
        TaskExecutor directExecutor = Runnable::run;
        service = new EmailClassificationService(
                emails,
                connections,
                model,
                properties,
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
        when(emails.lockNextClassificationDue(any(), any())).thenReturn(Optional.of(email));
        when(emails.findById(101L)).thenReturn(Optional.of(email));
        allowBudget();
        when(model.classify(anyString())).thenReturn("""
                {"category":"%s","reason":"A legfrissebb levél tartalma ezt támasztja alá.",
                 "evidence":"%s"}
                """.formatted(fixture.mockPrediction(), fixture.description()));

        service.classifyDueEmails();

        assertEquals(fixture.mockPrediction(), email.getCategory(), fixture.description());
        assertEquals("AI", email.getClassificationSource());
        assertEquals("v22", email.getClassificationVersion());
        assertEquals(fixture.mockPrediction() == EmailCategory.CLASSIFICATION_REVIEW ? "REVIEW" : "CLASSIFIED",
                email.getClassificationStatus());
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
    void emptyEmailSkipsModelAndRequiresManualClassification() {
        Email email = queuedEmail("");
        email.setSubject(null);
        email.setFromAddress(null);
        when(emails.lockNextClassificationDue(any(), any())).thenReturn(Optional.of(email));
        when(emails.findById(101L)).thenReturn(Optional.of(email));

        service.classifyDueEmails();

        assertEquals(EmailCategory.CLASSIFICATION_REVIEW, email.getCategory());
        assertEquals("REVIEW", email.getClassificationStatus());
        verify(model, never()).classify(anyString());
    }

    @Test
    void modelFailureSchedulesRetryWithoutClassifyingAsQuoteRequest() {
        Email email = queuedEmail("Szeretnék ajánlatot kérni új tetőfedési munkára.");
        when(emails.lockNextClassificationDue(any(), any())).thenReturn(Optional.of(email));
        when(emails.findById(101L)).thenReturn(Optional.of(email));
        allowBudget();
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
        when(emails.lockNextClassificationDue(any(), any())).thenReturn(Optional.of(email));
        when(emails.findById(101L)).thenReturn(Optional.of(email));
        allowBudget();
        when(model.classify(anyString())).thenThrow(new IllegalArgumentException("malformed response"));

        service.classifyDueEmails();

        assertEquals(EmailCategory.CLASSIFICATION_REVIEW, email.getCategory());
        assertEquals("FAILED", email.getClassificationStatus());
        assertNotNull(email.getClassificationReason());
        assertEquals(null, email.getClassificationRetryAt());
    }

    @Test
    void manualCategoryChangeIsNeverOverwrittenByAiCompletion() {
        Email email = queuedEmail("Új munkára kérek ajánlatot.");
        email.setCategory(EmailCategory.OTHER);
        email.setClassificationSource("MANUAL");
        when(emails.findById(101L)).thenReturn(Optional.of(email));

        service.finish(101L, new EmailClassificationService.Decision(
                EmailCategory.QUOTE_REQUEST, "AI decision", "ajánlatot"));

        assertEquals(EmailCategory.OTHER, email.getCategory());
        assertEquals("MANUAL", email.getClassificationSource());
        verify(emails, never()).save(email);
    }

    @Test
    void sendsBodyAndAllowlistedMetadataAsModelContext() throws Exception {
        Email email = queuedEmail("Új ereszcsatorna felszerelésére kérek árajánlatot.");
        email.setClassificationMetadata("{\"List-ID\":\"customers\"}");
        when(emails.lockNextClassificationDue(any(), any())).thenReturn(Optional.of(email));
        when(emails.findById(101L)).thenReturn(Optional.of(email));
        allowBudget();
        when(model.classify(anyString())).thenReturn("""
                {"category":"QUOTE_REQUEST","reason":"Új munkát kér.","evidence":"kérek árajánlatot"}
                """);

        service.classifyDueEmails();

        org.mockito.ArgumentCaptor<String> context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model).classify(context.capture());
        org.junit.jupiter.api.Assertions.assertTrue(context.getValue().contains("kérek árajánlatot"));
        org.junit.jupiter.api.Assertions.assertTrue(context.getValue().contains("customers"));
    }

    private void allowBudget() {
        doReturn(List.of(1)).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    private static Email queuedEmail(String body) {
        Email email = new Email();
        email.setId(101L);
        email.setSubject("Kivitelezési munka");
        email.setFromAddress("ugyfel@example.hu");
        email.setBodyText(body);
        email.setClassificationStatus("PENDING");
        email.setClassificationSource("PENDING");
        email.setClassificationRetryAt(NOW);
        email.setClassificationAttempts(0);
        return email;
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
