package com.buildassist.service;

import com.buildassist.calculation.CalculationOperation;
import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogItemType;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.InputSource;
import com.buildassist.dto.AiDtos;
import com.buildassist.dto.AiQuoteDtos;
import com.buildassist.dto.DraftDtos;
import com.buildassist.model.CatalogItem;
import com.buildassist.model.Email;
import com.buildassist.model.EmailDraft;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.EmailDraftRepository;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDraftServiceCoverageTest {

    private static final Long DRAFT_ID = 81L;
    private static final Long USER_ID = 17L;
    private static final Long EMAIL_ID = 42L;
    private static final String EMAIL_BODY = "20 konnektor szerelése.";

    @Mock private EmailRepository emailRepository;
    @Mock private CatalogItemRepository catalogItemRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private GmailConnectionRepository gmailConnectionRepository;
    @Mock private EmailDraftRepository emailDraftRepository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private SpringTemplateEngine templateEngine;
    @Mock private QuotePdfService quotePdfService;
    @Mock private QuoteEmailMessageFactory quoteEmailMessageFactory;
    @Mock private AiQuoteExtractionService aiQuoteExtractionService;
    @Mock private ChatModel chatModel;

    private ObjectMapper objectMapper;
    private QuoteCoverageService coverageService;
    private DraftQuoteSnapshotService snapshotService;
    private AiDraftService aiDraftService;
    private Email email;
    private CatalogItem catalogItem;
    private EmailDraft savedDraft;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        coverageService = new QuoteCoverageService(objectMapper);
        DraftQuoteSnapshotCodec codec = new DraftQuoteSnapshotCodec(objectMapper);
        snapshotService = new DraftQuoteSnapshotService(codec);
        AiQuoteSnapshotBuilder builder = new AiQuoteSnapshotBuilder(objectMapper);
        DraftQuotePricingService pricingService = new DraftQuotePricingService();
        aiDraftService = new AiDraftService(
                emailRepository, catalogItemRepository, companyProfileRepository,
                gmailConnectionRepository, emailDraftRepository, tokenEncryptionService,
                objectMapper, templateEngine, quotePdfService, quoteEmailMessageFactory,
                coverageService, aiQuoteExtractionService, builder, snapshotService, pricingService);

        User user = new User();
        user.setId(USER_ID);
        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        email = new Email();
        email.setId(EMAIL_ID);
        email.setBodyText(EMAIL_BODY);
        email.setFromAddress("customer@example.test");
        email.setSubject("Konnektorok szerelése");
        email.setGmailConnection(connection);

        CatalogCalculationConfig config = new CatalogCalculationConfig(
                Set.of(CalculationOperation.DIRECT), Map.of(), Map.of(), null, null);
        catalogItem = new CatalogItem();
        catalogItem.setId(900L);
        catalogItem.setName("Konnektor szerelési munkadíj");
        catalogItem.setUnit("db");
        catalogItem.setItemType(CatalogItemType.LABOR);
        catalogItem.setNetSellingUnitPrice(new BigDecimal("3500"));
        catalogItem.setEstimatedOwnLaborUnitCost(new BigDecimal("1700"));
        catalogItem.setCalculationConfigJson(objectMapper.writeValueAsString(config));

        when(emailRepository.findByIdAndGmailConnectionUserId(EMAIL_ID, USER_ID))
                .thenReturn(Optional.of(email));
        when(catalogItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(catalogItem));
        when(emailDraftRepository.save(any(EmailDraft.class))).thenAnswer(invocation -> {
            EmailDraft draft = invocation.getArgument(0);
            if (draft.getId() == null) {
                draft.setId(DRAFT_ID);
            }
            savedDraft = draft;
            return draft;
        });
        when(companyProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
    }

    @Test
    void savesAValidatedSnapshotAsTheOnlyQuoteLineSource() throws Exception {
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());

        DraftDtos.AnalysisResponse response = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftQuoteSnapshot persisted = snapshotService.load(savedDraft);

        assertEquals(DRAFT_ID, response.draftId());
        assertEquals(1, response.lineItems().size());
        assertEquals(new BigDecimal("3500"), response.lineItems().getFirst().unitPrice());
        assertEquals(new BigDecimal("70000"), response.netQuotePrice());
        assertTrue(persisted.lineItems().getFirst().lineItemId().startsWith("line-"));
        assertTrue(persisted.lineItems().getFirst().workId().startsWith("work-"));
        assertEquals(0, new BigDecimal("20").compareTo(
                persisted.lineItems().getFirst().requiredQuantity().value()));
        assertNull(savedDraft.getLineItemsJson());
        assertNull(savedDraft.getRequestedWorkItemsJson());
        assertNull(savedDraft.getUnmappedRequestsJson());
        assertEquals(response.quoteSnapshot(), persisted);
    }

    @Test
    void persistsUserQuantityOverridesInTheSnapshotAndRepricesFromTheCatalog() throws Exception {
        when(emailDraftRepository.findById(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        AiDtos.DraftLineItem original = analysis.lineItems().getFirst();
        DraftDtos.SaveDraftRequest override = new DraftDtos.SaveDraftRequest(
                analysis.clientName(),
                List.of(new AiDtos.DraftLineItem(
                        original.name(), original.unit(), new BigDecimal("15"),
                        original.unitPrice(), new BigDecimal("52500"),
                        original.lineItemId(), original.catalogItemId())),
                null,
                true);

        DraftDtos.GenerateDraftResponse saved = aiDraftService.saveDraft(DRAFT_ID, override, USER_ID);

        assertEquals(new BigDecimal("52500"), saved.netQuotePrice());
        assertEquals(0, new BigDecimal("15").compareTo(saved.lineItems().getFirst().quantity()));
        assertEquals(0, new BigDecimal("15").compareTo(
                saved.quoteSnapshot().lineItems().getFirst().userOverrideQuantity().value()));
        assertNull(savedDraft.getLineItemsJson());
    }

    @Test
    void pdfPreviewRendersOnlyValidatedSnapshotLines() throws Exception {
        when(emailDraftRepository.findById(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("snapshot-backed HTML");
        when(quotePdfService.render("snapshot-backed HTML")).thenReturn(new byte[]{1, 2, 3});
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftDtos.FinalizeDraftRequest previewRequest = new DraftDtos.FinalizeDraftRequest(
                analysis.clientName(), analysis.lineItems(), null, true);

        DraftDtos.GenerateDraftResponse preview =
                aiDraftService.previewDraft(DRAFT_ID, previewRequest, USER_ID);
        byte[] pdf = aiDraftService.generatePdf(DRAFT_ID, USER_ID);

        assertEquals("snapshot-backed HTML", preview.draftBody());
        assertEquals(List.of((byte) 1, (byte) 2, (byte) 3),
                java.util.stream.IntStream.range(0, pdf.length).mapToObj(index -> pdf[index]).toList());
        assertNull(savedDraft.getLineItemsJson());
        verify(quotePdfService).render("snapshot-backed HTML");
    }

    @Test
    void warningAcknowledgementCannotFinalizeAnInvalidCalculation() throws Exception {
        AiQuoteDtos.StructuredExtraction extraction = invalidOperationExtraction();
        when(emailDraftRepository.findById(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(extraction);

        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        assertEquals(com.buildassist.calculation.CalculationStatus.INVALID, analysis.calculationStatus());
        assertNull(analysis.netQuotePrice());
        assertEquals(0, analysis.lineItems().size());

        savedDraft.setReviewWarningsJson(objectMapper.writeValueAsString(List.of("manual warning")));
        AiDtos.CoverageResponse coverage = aiDraftService.getCoverage(DRAFT_ID, USER_ID);
        assertEquals(AiDtos.CoverageStatus.NEEDS_REVIEW, coverage.workItems().getFirst().status());
        DraftDtos.SaveDraftRequest unchanged = new DraftDtos.SaveDraftRequest(
                analysis.clientName(), analysis.lineItems(), null, true);
        AiDtos.CoverageWarning warning = coverage.warnings().stream()
                .filter(item -> item.text().equals("manual warning"))
                .findFirst().orElseThrow();
        aiDraftService.updateCoverageDecision(DRAFT_ID, warning.warningId(),
                new AiDtos.CoverageDecisionRequest(
                        AiDtos.CoverageStatus.INCLUDED, null, unchanged), USER_ID);
        AiDtos.CoverageResponse acknowledged = aiDraftService.getCoverage(DRAFT_ID, USER_ID);
        assertTrue(acknowledged.warnings().stream()
                .filter(item -> item.warningId().equals(warning.warningId()))
                .findFirst().orElseThrow().acknowledged());

        DraftDtos.FinalizeDraftRequest bypassAttempt = new DraftDtos.FinalizeDraftRequest(
                analysis.clientName(), analysis.lineItems(), null, true,
                true, acknowledged.quoteFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> aiDraftService.finalizeDraft(DRAFT_ID, bypassAttempt, USER_ID));
        verify(quotePdfService, never()).render(any(String.class));
        verify(gmailConnectionRepository, never()).findByUserId(anyLong());
    }

    private AiQuoteDtos.StructuredExtraction validExtraction() {
        return new AiQuoteDtos.StructuredExtraction(
                "Teszt Ügyfél",
                List.of(new AiQuoteDtos.WorkProposal(
                        "socket-work", "20 konnektor szerelése", true,
                        "CUSTOMER_EMAIL", "20 konnektor", new BigDecimal("20"), "db",
                        "MATERIAL_AND_LABOR", null, "Az ügyfél 20 konnektort kér.",
                        List.of(new AiQuoteDtos.LineProposal(
                                "socket-labor", catalogItem.getId(),
                                new AiQuoteDtos.PlanProposal(
                                        List.of(new AiQuoteDtos.InputProposal(
                                                "count", new BigDecimal("20"), "db",
                                                "CUSTOMER_EMAIL", "20 konnektor")),
                                        List.of(new AiQuoteDtos.StepProposal(
                                                "quantity", "DIRECT", List.of("count"),
                                                "db", null, null)),
                                        "quantity", List.of()),
                                null, null, "Darabszám alapján.", List.of())),
                        List.of(), List.of())),
                List.of(), List.of());
    }

    private AiQuoteDtos.StructuredExtraction invalidOperationExtraction() {
        AiQuoteDtos.StructuredExtraction extraction = validExtraction();
        AiQuoteDtos.WorkProposal work = extraction.workItems().getFirst();
        AiQuoteDtos.LineProposal original = work.proposedLines().getFirst();
        AiQuoteDtos.LineProposal invalid = new AiQuoteDtos.LineProposal(
                original.lineRef(), original.catalogItemId(),
                new AiQuoteDtos.PlanProposal(
                        original.calculationPlan().inputs(),
                        List.of(new AiQuoteDtos.StepProposal(
                                "invalid", "EVALUATE", List.of("count"), "db", null, null)),
                        "invalid", List.of()),
                null, null, "Nem engedélyezett művelet.", List.of());
        AiQuoteDtos.WorkProposal invalidWork = new AiQuoteDtos.WorkProposal(
                work.workRef(), work.description(), work.explicitlyRequested(), work.source(),
                work.sourceEvidence(), work.requestedQuantity(), work.requestedUnit(),
                work.settlementMode(), work.settlementEvidence(), work.explanation(),
                List.of(invalid), work.missingParameters(), work.assumptions());
        return new AiQuoteDtos.StructuredExtraction(
                extraction.clientName(), List.of(invalidWork), List.of(),
                List.of("A képletet ellenőrizd."));
    }
}
