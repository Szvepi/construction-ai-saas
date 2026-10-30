package com.buildassist.service;

import com.buildassist.calculation.CalculationOperation;
import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogItemType;
import com.buildassist.calculation.CatalogParameterConstraint;
import com.buildassist.calculation.CatalogParameterDefault;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.InputSource;
import com.buildassist.dto.AiDtos;
import com.buildassist.dto.AiQuoteDtos;
import com.buildassist.dto.DraftDtos;
import com.buildassist.model.CatalogItem;
import com.buildassist.model.CompanyProfile;
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
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        org.mockito.Mockito.lenient()
                .when(companyProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
                .when(emailDraftRepository.findById(DRAFT_ID))
                .thenAnswer(invocation -> Optional.ofNullable(savedDraft));
        org.mockito.Mockito.lenient()
                .when(emailDraftRepository.findByIdForUpdate(DRAFT_ID))
                .thenAnswer(invocation -> Optional.ofNullable(savedDraft));
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
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        AiDtos.DraftLineItem original = analysis.lineItems().getFirst();
        DraftDtos.SaveDraftRequest override = new DraftDtos.SaveDraftRequest(
                analysis.clientName(),
                List.of(new AiDtos.DraftLineItem(
                        original.name(), original.unit(), new BigDecimal("15"),
                        new BigDecimal("4000"), new BigDecimal("60000"),
                        original.lineItemId(), original.catalogItemId(), "NET",
                        true, "Külön kiszállási feltétel", original.catalogUnitPrice(),
                        false, original.calculationParameters())),
                null,
                true,
                0L);

        DraftDtos.GenerateDraftResponse saved = aiDraftService.saveDraft(DRAFT_ID, override, USER_ID);

        assertEquals(new BigDecimal("60000"), saved.netQuotePrice());
        assertEquals(0, new BigDecimal("15").compareTo(saved.lineItems().getFirst().quantity()));
        assertEquals(0, new BigDecimal("15").compareTo(
                saved.quoteSnapshot().lineItems().getFirst().userOverrideQuantity().value()));
        assertEquals(new BigDecimal("4000"),
                saved.quoteSnapshot().lineItems().getFirst().manualNetUnitPrice());
        assertEquals(new BigDecimal("3500"),
                saved.quoteSnapshot().lineItems().getFirst().catalogItem().netSellingUnitPrice());
        assertNull(savedDraft.getLineItemsJson());
    }

    @Test
    void pdfPreviewRendersOnlyValidatedSnapshotLines() throws Exception {
        when(emailDraftRepository.findById(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("snapshot-backed HTML");
        when(quotePdfService.render("snapshot-backed HTML")).thenReturn(new byte[]{1, 2, 3});
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftDtos.FinalizeDraftRequest previewRequest = new DraftDtos.FinalizeDraftRequest(
                analysis.clientName(), analysis.lineItems(), null, true, false, null, 0L);

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
    void appliesConfiguredVatRateToServerCalculatedNetTotal() throws Exception {
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID)).thenAnswer(invocation ->
                Optional.ofNullable(savedDraft));
        CompanyProfile profile = new CompanyProfile();
        profile.setVatRegistered(true);
        profile.setTaxRegime("VAT_REGISTERED");
        profile.setVatRate(new BigDecimal("18"));
        when(companyProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("taxed HTML");

        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        assertEquals(new BigDecimal("70000"), analysis.netQuotePrice());
        assertEquals(new BigDecimal("12600"), analysis.vatAmount());
        assertEquals(new BigDecimal("82600"), analysis.grossQuotePrice());
        assertTrue(analysis.taxConfigurationValid());

        aiDraftService.previewDraft(DRAFT_ID, new DraftDtos.FinalizeDraftRequest(
                analysis.clientName(), analysis.lineItems(), null, true, false, null, 0L), USER_ID);
        ArgumentCaptor<Context> context = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("draft"), context.capture());
        assertEquals("18", context.getValue().getVariable("vatRate"));
        assertEquals("12 600", context.getValue().getVariable("vatTotal"));
        assertEquals("82 600", context.getValue().getVariable("grossTotal"));
    }

    @Test
    void appliesAamTaxRegimeWithoutChargingVat() throws Exception {
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());

        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);

        assertEquals("AAM", analysis.taxRegime());
        assertTrue(analysis.taxConfigurationValid());
        assertEquals(new BigDecimal("70000"), analysis.netQuotePrice());
        assertEquals(BigDecimal.ZERO, analysis.vatAmount());
        assertEquals(new BigDecimal("70000"), analysis.grossQuotePrice());
    }

    @Test
    void rejectsStaleDraftVersionBeforeApplyingEdits() throws Exception {
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID))
                .thenAnswer(invocation -> Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);

        org.springframework.web.server.ResponseStatusException exception =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> aiDraftService.saveDraft(DRAFT_ID, new DraftDtos.SaveDraftRequest(
                                analysis.clientName(), analysis.lineItems(), null, true, 5L), USER_ID));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void mapsDistinctSameNamedUnmappedWorksAndRepeatingTheRequestIsIdempotent() throws Exception {
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID))
                .thenAnswer(invocation -> Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(twoUnmappedSameNameWorks());
        email.setBodyText("20 m2 festése. 35 m2 festése.");
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftQuoteSnapshot before = analysis.quoteSnapshot();
        DraftQuoteSnapshot.QuoteWorkSnapshot first = before.workItems().get(0);
        DraftQuoteSnapshot.QuoteWorkSnapshot second = before.workItems().get(1);
        assertEquals(first.originalRequest(), second.originalRequest());
        assertTrue(first.customerRequestedQuantity().value()
                .compareTo(second.customerRequestedQuantity().value()) != 0);

        DraftDtos.AddCatalogLineRequest request = new DraftDtos.AddCatalogLineRequest(
                first.workId(), catalogItem.getId(), new BigDecimal("20"), "db",
                analysis.draftVersion(), first.unmappedLines().getFirst().unmappedId());
        DraftDtos.GenerateDraftResponse mapped =
                aiDraftService.mapUnmappedWork(DRAFT_ID, request, USER_ID);
        DraftDtos.GenerateDraftResponse repeated =
                aiDraftService.mapUnmappedWork(DRAFT_ID, request, USER_ID);

        assertEquals(1, mapped.quoteSnapshot().lineItems().size());
        assertEquals(1, repeated.quoteSnapshot().lineItems().size());
        assertEquals(first.workId(), repeated.quoteSnapshot().lineItems().getFirst().workId());
        assertEquals(com.buildassist.calculation.WorkRecognitionStatus.MAPPED,
                repeated.quoteSnapshot().workItems().get(0).recognitionStatus());
        assertEquals(second.customerRequestedQuantity(),
                repeated.quoteSnapshot().workItems().get(1).customerRequestedQuantity());
        assertEquals(com.buildassist.calculation.WorkRecognitionStatus.UNMAPPED,
                repeated.quoteSnapshot().workItems().get(1).recognitionStatus());
    }

    @Test
    void unmappedCrushedStoneRequiresThicknessInsteadOfTreatingAreaAsVolume() throws Exception {
        configureCrushedStoneCatalog(null);
        email.setBodyText("Kocsibeálló 45 m2, zúzottkő alapréteg szükséges.");
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(crushedStoneExtraction());

        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftQuoteSnapshot.QuoteWorkSnapshot work = analysis.quoteSnapshot().workItems().getFirst();
        String unmappedId = work.unmappedLines().getFirst().unmappedId();

        DraftDtos.WorkMappingPreviewResponse preview = aiDraftService.previewUnmappedWorkMapping(
                analysis.draftId(), work.workId(), catalogItem.getId(), unmappedId, USER_ID);
        assertEquals(com.buildassist.calculation.CalculationStatus.NEEDS_INPUT, preview.calculationStatus());
        assertEquals("thickness", preview.parameters().stream()
                .filter(parameter -> parameter.id().equals("thickness"))
                .findFirst().orElseThrow().id());

        DraftDtos.GenerateDraftResponse mapped = aiDraftService.mapUnmappedWork(
                analysis.draftId(), new DraftDtos.AddCatalogLineRequest(
                        work.workId(), catalogItem.getId(), null, null, analysis.draftVersion(),
                        unmappedId, List.of()), USER_ID);

        DraftQuoteSnapshot.QuoteLineSnapshot line = mapped.quoteSnapshot().lineItems().getFirst();
        assertEquals(com.buildassist.calculation.CalculationStatus.NEEDS_INPUT,
                line.calculationResult().status());
        assertNull(line.requiredQuantity());
        assertNull(line.netLineTotal());
    }

    @Test
    void unmappedCrushedStoneUsesUserThicknessAndPersistsCalculatedQuantityAndPrice() throws Exception {
        configureCrushedStoneCatalog(null);
        email.setBodyText("Kocsibeálló 45 m2, zúzottkő alapréteg szükséges.");
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(crushedStoneExtraction());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftQuoteSnapshot.QuoteWorkSnapshot work = analysis.quoteSnapshot().workItems().getFirst();

        DraftDtos.GenerateDraftResponse mapped = aiDraftService.mapUnmappedWork(
                analysis.draftId(), new DraftDtos.AddCatalogLineRequest(
                        work.workId(), catalogItem.getId(), null, null, analysis.draftVersion(),
                        work.unmappedLines().getFirst().unmappedId(),
                        List.of(new DraftDtos.MappingParameterValue(
                                "thickness", new BigDecimal("15"), "cm"))), USER_ID);

        DraftQuoteSnapshot.QuoteLineSnapshot line = mapped.quoteSnapshot().lineItems().getFirst();
        assertEquals(com.buildassist.calculation.CalculationStatus.VALID,
                line.calculationResult().status());
        assertEquals(0, new BigDecimal("6.75").compareTo(line.requiredQuantity().value()));
        assertEquals(0, new BigDecimal("33750").compareTo(line.netLineTotal()));
        assertEquals(InputSource.USER_OVERRIDE, line.calculationPlan().inputs().stream()
                .filter(input -> input.id().equals("thickness")).findFirst().orElseThrow().source());
        assertEquals(0, new BigDecimal("0.15").compareTo(line.calculationPlan().inputs().stream()
                .filter(input -> input.id().equals("thickness")).findFirst().orElseThrow().value()));
        assertEquals(mapped.quoteSnapshot(), snapshotService.load(savedDraft));
    }

    @Test
    void changingMappedThicknessRecalculatesPriceAndInvalidatesLineFingerprint() throws Exception {
        configureCrushedStoneCatalog(null);
        email.setBodyText("Kocsibeálló 45 m2, zúzottkő alapréteg szükséges.");
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(crushedStoneExtraction());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftQuoteSnapshot.QuoteWorkSnapshot work = analysis.quoteSnapshot().workItems().getFirst();
        DraftDtos.GenerateDraftResponse mapped = aiDraftService.mapUnmappedWork(
                analysis.draftId(), new DraftDtos.AddCatalogLineRequest(
                        work.workId(), catalogItem.getId(), null, null, analysis.draftVersion(),
                        work.unmappedLines().getFirst().unmappedId(),
                        List.of(new DraftDtos.MappingParameterValue(
                                "thickness", new BigDecimal("15"), "cm"))), USER_ID);
        DraftQuoteSnapshot.QuoteLineSnapshot originalLine = mapped.quoteSnapshot().lineItems().getFirst();
        String originalFingerprint = coverageService.lineItemFingerprint(mapped.quoteSnapshot(), originalLine);
        AiDtos.DraftLineItem originalDto = mapped.lineItems().getFirst();
        List<AiDtos.CalculationParameter> updatedParameters = originalDto.calculationParameters().stream()
                .map(parameter -> parameter.id().equals("thickness")
                        ? new AiDtos.CalculationParameter(
                                parameter.id(), new BigDecimal("0.20"), "m", "USER_OVERRIDE", true, false)
                        : parameter)
                .toList();
        AiDtos.DraftLineItem updatedDto = new AiDtos.DraftLineItem(
                originalDto.name(), originalDto.unit(), originalDto.quantity(), originalDto.unitPrice(),
                originalDto.subtotal(), originalDto.lineItemId(), originalDto.catalogItemId(),
                originalDto.priceBasis(), originalDto.priceOverridden(), originalDto.priceOverrideReason(),
                originalDto.catalogUnitPrice(), originalDto.assumptionsApproved(), updatedParameters,
                originalDto.userOverrideLine(), originalDto.itemType());

        DraftDtos.GenerateDraftResponse updated = aiDraftService.saveDraft(
                DRAFT_ID, new DraftDtos.SaveDraftRequest(
                        mapped.clientName(), List.of(updatedDto), null, true, mapped.draftVersion()), USER_ID);

        DraftQuoteSnapshot.QuoteLineSnapshot updatedLine = updated.quoteSnapshot().lineItems().getFirst();
        assertEquals(0, new BigDecimal("9").compareTo(updatedLine.requiredQuantity().value()));
        assertEquals(0, new BigDecimal("45000").compareTo(updatedLine.netLineTotal()));
        assertNotEquals(originalFingerprint,
                coverageService.lineItemFingerprint(updated.quoteSnapshot(), updatedLine));
    }

    @Test
    void unmappedCrushedStoneUsesOnlyConfiguredCatalogDefaultAndShowsItForReview() throws Exception {
        configureCrushedStoneCatalog(new CatalogParameterDefault(
                new BigDecimal("0.15"), "m", InputSource.CATALOG_DEFAULT));
        email.setBodyText("Kocsibeálló 45 m2, zúzottkő alapréteg szükséges.");
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(crushedStoneExtraction());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftQuoteSnapshot.QuoteWorkSnapshot work = analysis.quoteSnapshot().workItems().getFirst();

        DraftDtos.WorkMappingPreviewResponse preview = aiDraftService.previewUnmappedWorkMapping(
                analysis.draftId(), work.workId(), catalogItem.getId(),
                work.unmappedLines().getFirst().unmappedId(), USER_ID);
        DraftDtos.MappingParameterDescriptor thickness = preview.parameters().stream()
                .filter(parameter -> parameter.id().equals("thickness")).findFirst().orElseThrow();
        assertEquals("CATALOG_DEFAULT", thickness.source());
        assertEquals("cm", thickness.displayUnit());
        assertEquals(0, new BigDecimal("15").compareTo(thickness.displayValue()));

        DraftDtos.GenerateDraftResponse mapped = aiDraftService.mapUnmappedWork(
                analysis.draftId(), new DraftDtos.AddCatalogLineRequest(
                        work.workId(), catalogItem.getId(), null, null, analysis.draftVersion(),
                        work.unmappedLines().getFirst().unmappedId(), List.of()), USER_ID);
        DraftQuoteSnapshot.QuoteLineSnapshot line = mapped.quoteSnapshot().lineItems().getFirst();
        assertEquals(0, new BigDecimal("6.75").compareTo(line.requiredQuantity().value()));
        assertEquals(InputSource.CATALOG_DEFAULT, line.calculationPlan().inputs().stream()
                .filter(input -> input.id().equals("thickness")).findFirst().orElseThrow().source());
    }

    @Test
    void verifiedCustomerThicknessTakesPrecedenceOverCatalogDefault() throws Exception {
        configureCrushedStoneCatalog(new CatalogParameterDefault(
                new BigDecimal("0.15"), "m", InputSource.CATALOG_DEFAULT));
        email.setBodyText("Kocsibeálló 45 m2, a zúzottkő alapréteg vastagsága 20 cm.");
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(crushedStoneExtractionWithCustomerThickness());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        DraftQuoteSnapshot.QuoteWorkSnapshot work = analysis.quoteSnapshot().workItems().getFirst();

        DraftDtos.GenerateDraftResponse mapped = aiDraftService.mapUnmappedWork(
                analysis.draftId(), new DraftDtos.AddCatalogLineRequest(
                        work.workId(), catalogItem.getId(), null, null, analysis.draftVersion(),
                        work.unmappedLines().getFirst().unmappedId(), List.of()), USER_ID);

        DraftQuoteSnapshot.QuoteLineSnapshot line = mapped.quoteSnapshot().lineItems().getFirst();
        assertEquals(0, new BigDecimal("9").compareTo(line.requiredQuantity().value()));
        assertEquals(InputSource.CUSTOMER_EMAIL, line.calculationPlan().inputs().stream()
                .filter(input -> input.id().equals("thickness")).findFirst().orElseThrow().source());
    }

    @Test
    void manualQuoteLineCanBeAddedEditedAndDeletedWithoutChangingCatalog() throws Exception {
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID))
                .thenAnswer(invocation -> Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        DraftDtos.AnalysisResponse analysis = aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);
        String workId = analysis.quoteSnapshot().workItems().getFirst().workId();

        DraftDtos.GenerateDraftResponse added = aiDraftService.addManualLine(
                DRAFT_ID, new DraftDtos.AddManualLineRequest(
                        "Egyszeri kiszállási díj", new BigDecimal("2"), "db",
                        new BigDecimal("1250"), CatalogItemType.OTHER, workId, 0L), USER_ID);
        DraftQuoteSnapshot.QuoteLineSnapshot manual = added.quoteSnapshot().lineItems().stream()
                .filter(DraftQuoteSnapshot.QuoteLineSnapshot::userOverrideLine)
                .findFirst().orElseThrow();
        assertNull(manual.catalogItemId());
        assertEquals(new BigDecimal("2500"), manual.netLineTotal());
        assertEquals(new BigDecimal("3500"), catalogItem.getNetSellingUnitPrice());

        DraftDtos.GenerateDraftResponse edited = aiDraftService.updateManualLine(
                DRAFT_ID, manual.lineItemId(), new DraftDtos.UpdateManualLineRequest(
                        "Módosított kiszállási díj", new BigDecimal("3"), "db",
                        new BigDecimal("1000"), CatalogItemType.OTHER, 0L), USER_ID);
        DraftQuoteSnapshot.QuoteLineSnapshot updated = edited.quoteSnapshot().lineItems().stream()
                .filter(line -> line.lineItemId().equals(manual.lineItemId())).findFirst().orElseThrow();
        assertEquals("Módosított kiszállási díj", updated.catalogItem().name());
        assertEquals(new BigDecimal("3000"), updated.netLineTotal());

        DraftDtos.GenerateDraftResponse deleted = aiDraftService.deleteManualLine(
                DRAFT_ID, manual.lineItemId(), 0L, USER_ID);
        assertTrue(deleted.quoteSnapshot().lineItems().stream()
                .noneMatch(line -> line.lineItemId().equals(manual.lineItemId())));
        assertEquals(1, deleted.quoteSnapshot().lineItems().size());
    }

    @Test
    void rejectsManualLineEditsForAnotherTenantsDraft() throws Exception {
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID))
                .thenAnswer(invocation -> Optional.ofNullable(savedDraft));
        when(aiQuoteExtractionService.extract(eq(email), eq(List.of(catalogItem)), eq(null)))
                .thenReturn(validExtraction());
        aiDraftService.analyzeEmail(EMAIL_ID, USER_ID);

        assertThrows(IllegalArgumentException.class, () -> aiDraftService.addManualLine(
                DRAFT_ID, new DraftDtos.AddManualLineRequest(
                        "Kiszállás", BigDecimal.ONE, "db", new BigDecimal("1000"),
                        CatalogItemType.OTHER, null, 0L), USER_ID + 1));
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
        assertEquals(1, analysis.lineItems().size());
        assertNull(analysis.lineItems().getFirst().quantity());
        assertNull(analysis.lineItems().getFirst().subtotal());

        savedDraft.setReviewWarningsJson(objectMapper.writeValueAsString(List.of("manual warning")));
        AiDtos.CoverageResponse coverage = aiDraftService.getCoverage(DRAFT_ID, USER_ID);
        assertEquals(AiDtos.CoverageStatus.NEEDS_REVIEW, coverage.workItems().getFirst().status());
        DraftDtos.SaveDraftRequest unchanged = new DraftDtos.SaveDraftRequest(
                analysis.clientName(), analysis.lineItems(), null, true, 0L);
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
                true, acknowledged.quoteFingerprint(), 0L);
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

    private void configureCrushedStoneCatalog(CatalogParameterDefault thicknessDefault) throws Exception {
        Map<String, CatalogParameterDefault> defaults = thicknessDefault == null
                ? Map.of() : Map.of("thickness", thicknessDefault);
        CatalogCalculationConfig config = new CatalogCalculationConfig(
                Set.of(CalculationOperation.VOLUME), defaults,
                Map.of("thickness", new CatalogParameterConstraint(
                        "m", true, new BigDecimal("0.05"), new BigDecimal("0.50"))),
                null, null);
        catalogItem.setName("Zúzottkő alapréteg");
        catalogItem.setUnit("m3");
        catalogItem.setItemType(CatalogItemType.MATERIAL);
        catalogItem.setNetSellingUnitPrice(new BigDecimal("5000"));
        catalogItem.setNetProcurementUnitCost(new BigDecimal("3000"));
        catalogItem.setEstimatedOwnLaborUnitCost(null);
        catalogItem.setCalculationConfigJson(objectMapper.writeValueAsString(config));
    }

    private AiQuoteDtos.StructuredExtraction crushedStoneExtraction() {
        return new AiQuoteDtos.StructuredExtraction(
                "Teszt Ügyfél",
                List.of(new AiQuoteDtos.WorkProposal(
                        "driveway", "Kocsibeálló zúzottkő alaprétege", true,
                        "CUSTOMER_EMAIL", "45 m2", new BigDecimal("45"), "m2",
                        "MATERIAL_AND_LABOR", null, "A kocsibeálló területére alapréteg készül.",
                        List.of(new AiQuoteDtos.LineProposal(
                                "crushed-stone", null,
                                new AiQuoteDtos.PlanProposal(
                                        List.of(
                                                new AiQuoteDtos.InputProposal(
                                                        "area", new BigDecimal("45"), "m2",
                                                        "CUSTOMER_EMAIL", "45 m2"),
                                                new AiQuoteDtos.InputProposal(
                                                        "thickness", null, "m", "AI_ASSUMPTION", null)),
                                        List.of(new AiQuoteDtos.StepProposal(
                                                "volume", "VOLUME", List.of("area", "thickness"),
                                                "m3", null, null)),
                                        "volume", List.of()),
                                null, null, "Terület és rétegvastagság alapján.", List.of())),
                        List.of(new AiQuoteDtos.MissingParameter(
                                "thickness", "m", "A rétegvastagság hiányzik.")), List.of())),
                List.of(), List.of());
    }

    private AiQuoteDtos.StructuredExtraction crushedStoneExtractionWithCustomerThickness() {
        AiQuoteDtos.StructuredExtraction base = crushedStoneExtraction();
        AiQuoteDtos.WorkProposal originalWork = base.workItems().getFirst();
        AiQuoteDtos.LineProposal originalLine = originalWork.proposedLines().getFirst();
        AiQuoteDtos.PlanProposal originalPlan = originalLine.calculationPlan();
        List<AiQuoteDtos.InputProposal> inputs = originalPlan.inputs().stream()
                .map(input -> input.id().equals("thickness")
                        ? new AiQuoteDtos.InputProposal(
                                "thickness", new BigDecimal("20"), "cm", "CUSTOMER_EMAIL", "20 cm")
                        : input)
                .toList();
        AiQuoteDtos.LineProposal line = new AiQuoteDtos.LineProposal(
                originalLine.lineRef(), originalLine.catalogItemId(),
                new AiQuoteDtos.PlanProposal(inputs, originalPlan.steps(),
                        originalPlan.outputReference(), originalPlan.reviewReasons()),
                originalLine.procurementOutputReference(), originalLine.procurementPackageCountReference(),
                originalLine.explanation(), originalLine.assumptions());
        AiQuoteDtos.WorkProposal work = new AiQuoteDtos.WorkProposal(
                originalWork.workRef(), originalWork.description(), originalWork.explicitlyRequested(),
                originalWork.source(), originalWork.sourceEvidence(), originalWork.requestedQuantity(),
                originalWork.requestedUnit(), originalWork.settlementMode(), originalWork.settlementEvidence(),
                originalWork.explanation(), List.of(line), List.of(), originalWork.assumptions());
        return new AiQuoteDtos.StructuredExtraction(base.clientName(), List.of(work),
                base.unmappedRequests(), base.reviewWarnings());
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

    private AiQuoteDtos.StructuredExtraction twoUnmappedSameNameWorks() {
        return new AiQuoteDtos.StructuredExtraction(
                "Teszt Ügyfél",
                List.of(
                        unmappedWork("same-name-a", new BigDecimal("20")),
                        unmappedWork("same-name-b", new BigDecimal("35"))),
                List.of(), List.of());
    }

    private AiQuoteDtos.WorkProposal unmappedWork(String ref, BigDecimal quantity) {
        return new AiQuoteDtos.WorkProposal(
                ref, "Festés", true, "CUSTOMER_EMAIL", quantity + " m2 festése.",
                quantity, "m2", "MATERIAL_AND_LABOR", null, "Festési munkafázis.",
                List.of(new AiQuoteDtos.LineProposal(
                        ref + "-line", null, null, null, null,
                        "Nincs még katalógushoz rendelve.", List.of())),
                List.of(), List.of());
    }
}
