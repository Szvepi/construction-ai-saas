package com.buildassist.service;

import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.CalculationInput;
import com.buildassist.calculation.CalculationOperation;
import com.buildassist.calculation.CalculationPlan;
import com.buildassist.calculation.CalculationResult;
import com.buildassist.calculation.CalculationStep;
import com.buildassist.calculation.ChargeComponent;
import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogParameterConstraint;
import com.buildassist.calculation.CatalogParameterDefault;
import com.buildassist.calculation.CatalogItemType;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.DraftQuoteSnapshot.QuoteLineSnapshot;
import com.buildassist.calculation.DraftQuoteSnapshot.QuoteWorkSnapshot;
import com.buildassist.calculation.NetMoney;
import com.buildassist.calculation.Quantity;
import com.buildassist.calculation.QuantityUnit;
import com.buildassist.calculation.WorkRecognitionStatus;
import com.buildassist.calculation.InputSource;
import com.buildassist.dto.AiDtos.CoverageDecision;
import com.buildassist.dto.AiDtos.CoverageDecisionRequest;
import com.buildassist.dto.AiDtos.CoverageResponse;
import com.buildassist.dto.AiDtos.CoverageStatus;
import com.buildassist.dto.AiDtos.CoverageWarning;
import com.buildassist.dto.AiDtos.DraftLineItem;
import com.buildassist.dto.AiDtos.DraftPricingInfo;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
public class AiDraftService {

    private final EmailRepository emailRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final EmailDraftRepository emailDraftRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SpringTemplateEngine templateEngine;
    private final QuotePdfService quotePdfService;
    private final QuoteEmailMessageFactory quoteEmailMessageFactory;
    private final QuoteCoverageService quoteCoverageService;
    private final AiQuoteExtractionService aiQuoteExtractionService;
    private final AiQuoteSnapshotBuilder snapshotBuilder;
    private final DraftQuoteSnapshotService snapshotService;
    private final DraftQuotePricingService pricingService;
    private final DraftQuoteLineEditor lineEditor = new DraftQuoteLineEditor();

    public AiDraftService(
            EmailRepository emailRepository,
            CatalogItemRepository catalogItemRepository,
            CompanyProfileRepository companyProfileRepository,
            GmailConnectionRepository gmailConnectionRepository,
            EmailDraftRepository emailDraftRepository,
            TokenEncryptionService tokenEncryptionService,
            ObjectMapper objectMapper,
            SpringTemplateEngine templateEngine,
            QuotePdfService quotePdfService,
            QuoteEmailMessageFactory quoteEmailMessageFactory,
            QuoteCoverageService quoteCoverageService,
            AiQuoteExtractionService aiQuoteExtractionService,
            AiQuoteSnapshotBuilder snapshotBuilder,
            DraftQuoteSnapshotService snapshotService,
            DraftQuotePricingService pricingService) {
        this.emailRepository = emailRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.companyProfileRepository = companyProfileRepository;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.emailDraftRepository = emailDraftRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.templateEngine = templateEngine;
        this.quotePdfService = quotePdfService;
        this.quoteEmailMessageFactory = quoteEmailMessageFactory;
        this.quoteCoverageService = quoteCoverageService;
        this.aiQuoteExtractionService = aiQuoteExtractionService;
        this.snapshotBuilder = snapshotBuilder;
        this.snapshotService = snapshotService;
        this.pricingService = pricingService;
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse generateAndSaveDraft(Long emailId, Long userId) {
        DraftDtos.AnalysisResponse analysis = analyzeEmail(emailId, userId);
        EmailDraft draft = emailDraftRepository.findById(analysis.draftId())
                .orElseThrow(() -> new IllegalStateException("The generated quote draft was not persisted."));
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        if (overallStatus(snapshot, draft) != CalculationStatus.VALID) {
            return toResponse(draft, userId);
        }
        CoverageResponse coverage = coverageFor(draft);
        if (!coverage.analysisFresh() || !coverage.unresolvedIssues().isEmpty()) {
            return toResponse(draft, userId);
        }
        return finalizeDraft(draft.getId(), new DraftDtos.FinalizeDraftRequest(
                analysis.clientName(), analysis.lineItems(), analysis.additionalNotes(),
                analysis.includeAdditionalNotes(), false, coverage.quoteFingerprint(),
                analysis.draftVersion()), userId);
    }

    @Transactional
    public DraftDtos.AnalysisResponse analyzeEmail(Long emailId, Long userId) {
        return analyzeEmail(emailId, userId, null);
    }

    @Transactional
    public DraftDtos.AnalysisResponse analyzeEmail(Long emailId, Long userId, String additionalNotes) {
        Email email = emailRepository.findByIdAndGmailConnectionUserId(emailId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Email not found or access denied"));
        List<CatalogItem> catalogItems = catalogItemRepository.findAllByUserId(userId);
        String normalizedNotes = normalizeOptional(additionalNotes);
        var extraction = aiQuoteExtractionService.extract(email, catalogItems, normalizedNotes);
        AiQuoteSnapshotBuilder.BuildResult build = snapshotBuilder.build(
                extraction, catalogItems, email.getBodyText(), normalizedNotes);
        DraftQuoteSnapshot snapshot = snapshotService.seal(build.snapshot());

        EmailDraft draft = new EmailDraft();
        draft.setEmail(email);
        draft.setDraftBody("");
        draft.setClientName(extraction.clientName());
        draft.setAdditionalNotes(normalizedNotes);
        draft.setIncludeAdditionalNotes(true);
        draft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
        draft.setLineItemsJson(null);
        draft.setUnmappedRequestsJson(null);
        draft.setRequestedWorkItemsJson(null);
        draft.setReviewWarningsJson(writeJson(build.reviewWarnings()));
        draft.setCoverageDecisionsJson("[]");
        draft.setAcknowledgedWarningsJson("[]");
        draft.setAnalysisFingerprint(
                quoteCoverageService.analysisFingerprint(email.getBodyText(), normalizedNotes));
        snapshotService.save(draft, snapshot);

        EmailDraft savedDraft = emailDraftRepository.save(draft);
        CoverageResponse coverage = coverageFor(savedDraft);
        return new DraftDtos.AnalysisResponse(
                savedDraft.getId(),
                extraction.clientName(),
                lineItems(snapshot, savedDraft, userId),
                build.unmappedRequests(),
                build.reviewWarnings(),
                savedDraft.getAdditionalNotes(),
                savedDraft.isIncludeAdditionalNotes(),
                isVatRegistered(userId),
                coverage.workItems(),
                coverage.warnings(),
                coverage.analysisFresh(),
                snapshot,
                overallStatus(snapshot, savedDraft),
                netQuotePrice(snapshot, savedDraft),
                taxSummary(snapshot, savedDraft, userId).taxRegime(),
                taxSummary(snapshot, savedDraft, userId).vatRate(),
                taxSummary(snapshot, savedDraft, userId).vatAmount(),
                taxSummary(snapshot, savedDraft, userId).grossTotal(),
                taxSummary(snapshot, savedDraft, userId).configurationValid(),
                savedDraft.getVersion());
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse saveDraft(
            Long draftId, DraftDtos.SaveDraftRequest request, Long userId) {
        if (request == null) {
            throw new IllegalArgumentException("Save request is required.");
        }
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, request.expectedVersion());
        applyDraftChanges(draft, request, userId);
        emailDraftRepository.save(draft);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional(readOnly = true)
    public DraftDtos.GenerateDraftResponse getDraft(Long draftId, Long userId) {
        return toResponse(findAuthorizedDraft(draftId, userId, false), userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse addCatalogLine(
            Long draftId, DraftDtos.AddCatalogLineRequest request, Long userId) {
        if (request == null || request.workId() == null || request.catalogItemId() == null
                || request.quantity() == null || request.unit() == null) {
            throw new IllegalArgumentException("Munka, katalógustétel, mennyiség és mértékegység szükséges.");
        }
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, request.expectedVersion());
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        DraftQuoteSnapshot.QuoteWorkSnapshot work = snapshot.workItems().stream()
                .filter(item -> item.workId().equals(request.workId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A kiválasztott munkafázis nem található."));
        CatalogItem catalog = catalogItemRepository.findAllByUserId(userId).stream()
                .filter(item -> request.catalogItemId().equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A katalógustétel nem található."));
        CatalogCalculationConfig config = readCatalogConfig(catalog);
        Quantity measured = new Quantity(request.quantity(), QuantityUnit.fromCode(request.unit()));
        Quantity required = measured.convertTo(QuantityUnit.fromCode(catalog.getUnit()));
        if (required.value().signum() <= 0) {
            throw new IllegalArgumentException("A mennyiségnek pozitívnak kell lennie.");
        }
        QuoteLineSnapshot existingLine = snapshot.lineItems().stream()
                .filter(line -> Objects.equals(line.workId(), work.workId()))
                .filter(line -> Objects.equals(line.catalogItemId(), catalog.getId()))
                .filter(line -> line.requiredQuantity() != null
                        && line.requiredQuantity().convertTo(required.unit()).value()
                        .compareTo(required.value()) == 0)
                .findFirst().orElse(null);
        if (existingLine != null) {
            return toResponse(draft, userId);
        }
        if (!config.allowedCalculations().isEmpty()
                && !config.allowedCalculations().contains(CalculationOperation.DIRECT)) {
            throw new IllegalArgumentException(
                    "Ehhez a katalógustételhez nincs engedélyezett közvetlen mennyiség. A munkát AI-újraelemzéssel kell illeszteni.");
        }
        String lineId = new com.buildassist.calculation.QuoteIdGenerator().newLineItemId();
        CalculationPlan plan = new CalculationPlan(
                List.of(new CalculationInput(
                        "contractorQuantity", required.value(), catalog.getUnit(),
                        InputSource.USER_OVERRIDE, true, "Kivitelező által megadott ajánlati mennyiség")),
                List.of(new CalculationStep(
                        "directQuantity", CalculationOperation.DIRECT,
                        List.of("contractorQuantity"), catalog.getUnit(), null, null)),
                "directQuantity", List.of());
        CalculationResult calculation = new com.buildassist.calculation.DeterministicQuantityCalculator()
                .calculate(plan);
        List<ChargeComponent> components = switch (catalog.getItemType()) {
            case MATERIAL -> List.of(ChargeComponent.MATERIAL);
            case LABOR -> List.of(ChargeComponent.LABOR);
            case COMBINED -> List.of(ChargeComponent.MATERIAL, ChargeComponent.LABOR);
            case OTHER -> List.of(ChargeComponent.OTHER);
        };
        boolean chargeable = !(catalog.getItemType() == CatalogItemType.MATERIAL
                && work.settlementMode() != com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR)
                && !(catalog.getItemType() == CatalogItemType.COMBINED
                && work.settlementMode() != com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR);
        BigDecimal allowancePercent = config.materialAllowancePercent();
        Quantity adjusted = catalog.getItemType() == CatalogItemType.MATERIAL
                && work.settlementMode() == com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR
                && allowancePercent != null
                ? new Quantity(required.value().multiply(BigDecimal.ONE.add(allowancePercent.movePointLeft(2))),
                        required.unit())
                : required;
        Quantity procurement = catalog.getItemType() == CatalogItemType.MATERIAL
                ? work.settlementMode() == com.buildassist.calculation.SettlementMode.CUSTOMER_SUPPLIED_MATERIAL
                ? new Quantity(BigDecimal.ZERO, required.unit()) : adjusted
                : null;
        List<DraftQuoteSnapshot.RequirementSnapshot> requirements = new ArrayList<>();
        for (ChargeComponent component : components) {
            BigDecimal unitCost = component == ChargeComponent.MATERIAL
                    ? catalog.getNetProcurementUnitCost()
                    : component == ChargeComponent.LABOR ? catalog.getEstimatedOwnLaborUnitCost() : null;
            Quantity charged = chargeable
                    ? component == ChargeComponent.MATERIAL ? adjusted : required
                    : new Quantity(BigDecimal.ZERO, required.unit());
            requirements.add(new DraftQuoteSnapshot.RequirementSnapshot(
                    "req-" + lineId + "-" + component.name().toLowerCase(Locale.ROOT),
                    component, required,
                    component == ChargeComponent.MATERIAL ? adjusted : null,
                    component == ChargeComponent.MATERIAL ? procurement : null,
                    charged, unitCost, null));
        }
        DraftQuoteSnapshot.CatalogItemSnapshot catalogSnapshot = new DraftQuoteSnapshot.CatalogItemSnapshot(
                catalog.getName(), catalog.getUnit(), catalog.getItemType(), catalog.getNetSellingUnitPrice(),
                catalog.getNetProcurementUnitCost(), catalog.getEstimatedOwnLaborUnitCost(), config);
        DraftQuoteSnapshot.QuoteLineSnapshot newLine = new QuoteLineSnapshot(
                lineId, work.workId(), catalog.getId(), catalogSnapshot, work.settlementMode(),
                requirements, plan, calculation, required, procurement,
                catalog.getItemType() == CatalogItemType.MATERIAL ? adjusted : required,
                null, false, null, "A kivitelező által a katalógusból hozzáadott tétel.",
                chargeable ? null : BigDecimal.ZERO, chargeable, null, List.of(), null, null);
        List<DraftQuoteSnapshot.UnmappedLineSnapshot> unmappedLines = new ArrayList<>(work.unmappedLines());
        if (!unmappedLines.isEmpty()) {
            if (request.unmappedId() != null) {
                boolean removed = unmappedLines.removeIf(item ->
                        Objects.equals(request.unmappedId(), item.unmappedId()));
                if (!removed) {
                    throw new IllegalArgumentException("A fel nem térképezett munkajavaslat már nem található.");
                }
            } else {
                unmappedLines.removeFirst();
            }
        }
        DraftQuoteSnapshot.QuoteWorkSnapshot mappedWork = new DraftQuoteSnapshot.QuoteWorkSnapshot(
                work.workId(), work.originalRequest(), work.source(), work.customerRequestedQuantity(),
                work.customerRequestedUnit(), work.settlementMode(),
                unmappedLines.isEmpty() ? WorkRecognitionStatus.MAPPED
                        : WorkRecognitionStatus.PARTIALLY_MAPPED,
                catalog.getId(), work.sourceEvidence(), work.explanation(), work.explicitlyRequested(),
                work.assumptions(), work.missingParameters(), unmappedLines);
        List<DraftQuoteSnapshot.QuoteWorkSnapshot> updatedWorks = snapshot.workItems().stream()
                .map(item -> item.workId().equals(work.workId()) ? mappedWork : item)
                .toList();
        List<QuoteLineSnapshot> updatedLines = new ArrayList<>(snapshot.lineItems());
        updatedLines.add(newLine);
        DraftQuotePricingService.RepricingResult repriced = pricingService.reprice(
                new DraftQuoteSnapshot(snapshot.schemaVersion(), null, updatedWorks, updatedLines));
        snapshotService.save(draft, repriced.snapshot());
        draft.setDraftBody("");
        draft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
        emailDraftRepository.save(draft);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse mapUnmappedWork(
            Long draftId, DraftDtos.AddCatalogLineRequest request, Long userId) {
        if (request == null || request.workId() == null) {
            throw new IllegalArgumentException("A fel nem térképezett munkát ki kell választani.");
        }
        if (request.catalogItemId() == null) {
            throw new IllegalArgumentException("Katalógustétel szükséges.");
        }
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, request.expectedVersion());
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        QuoteWorkSnapshot work = snapshot.workItems().stream()
                .filter(item -> Objects.equals(item.workId(), request.workId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A kiválasztott munka nem található."));
        if (work.unmappedLines().isEmpty()) {
            QuoteLineSnapshot alreadyMapped = snapshot.lineItems().stream()
                    .filter(line -> Objects.equals(line.workId(), work.workId()))
                    .filter(line -> Objects.equals(line.catalogItemId(), request.catalogItemId()))
                    .filter(line -> request.unmappedId() != null
                            ? Objects.equals(line.explanation(),
                                    "Munkához rendelt katalógustétel: " + request.unmappedId())
                            : request.quantity() != null && line.requiredQuantity() != null
                                    && line.requiredQuantity().value().compareTo(request.quantity()) == 0)
                    .findFirst().orElse(null);
            if (alreadyMapped != null) {
                return toResponse(draft, userId);
            }
            throw new IllegalArgumentException("A kiválasztott munkának nincs fel nem térképezett tétele.");
        }
        if (request.unmappedId() != null && work.unmappedLines().stream()
                .noneMatch(item -> Objects.equals(item.unmappedId(), request.unmappedId()))) {
            CatalogItem existingCatalog = findTenantCatalogItem(userId, request.catalogItemId());
            QuoteLineSnapshot mapped = snapshot.lineItems().stream()
                    .filter(line -> Objects.equals(line.workId(), work.workId()))
                    .filter(line -> Objects.equals(line.catalogItemId(), existingCatalog.getId()))
                    .filter(line -> line.explanation() != null
                            && line.explanation().equals("Munkához rendelt katalógustétel: " + request.unmappedId()))
                    .findFirst().orElse(null);
            if (mapped != null) {
                return toResponse(draft, userId);
            }
            throw new IllegalArgumentException("A kiválasztott munkajavaslat már nem található.");
        }
        DraftQuoteSnapshot.UnmappedLineSnapshot unmapped = request.unmappedId() == null
                ? work.unmappedLines().getFirst()
                : work.unmappedLines().stream()
                .filter(item -> Objects.equals(item.unmappedId(), request.unmappedId()))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("A kiválasztott munkajavaslat már nem található."));
        CatalogItem catalog = findTenantCatalogItem(userId, request.catalogItemId());
        CatalogCalculationConfig config = readCatalogConfig(catalog);
        Email email = draft.getEmail();
        CalculationPlan plan = mappingPlan(work, unmapped, catalog, config, request,
                email == null ? null : email.getBodyText(), draft.getAdditionalNotes());
        if (plan == null) {
            throw new IllegalArgumentException(
                    "Ehhez a tételhez nincs biztonságosan használható számítási terv. Válassz másik katalógustételt.");
        }
        CalculationResult result = new com.buildassist.calculation.DeterministicQuantityCalculator()
                .calculate(plan);
        if (result.status() == CalculationStatus.INVALID) {
            throw new IllegalArgumentException("A katalógustétel számítási terve érvénytelen; a tétel nem lett hozzáadva.");
        }

        String lineId = new com.buildassist.calculation.QuoteIdGenerator().newLineItemId();
        Quantity required = result.status() == CalculationStatus.VALID
                ? result.quantity().convertTo(QuantityUnit.fromCode(catalog.getUnit())) : null;
        List<ChargeComponent> components = switch (catalog.getItemType()) {
            case MATERIAL -> List.of(ChargeComponent.MATERIAL);
            case LABOR -> List.of(ChargeComponent.LABOR);
            case COMBINED -> List.of(ChargeComponent.MATERIAL, ChargeComponent.LABOR);
            case OTHER -> List.of(ChargeComponent.OTHER);
        };
        boolean chargeable = !(catalog.getItemType() == CatalogItemType.MATERIAL
                && work.settlementMode() != com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR)
                && !(catalog.getItemType() == CatalogItemType.COMBINED
                && work.settlementMode() != com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR);
        BigDecimal allowancePercent = config.materialAllowancePercent();
        Quantity adjusted = required == null ? null
                : allowancePercent == null || allowancePercent.signum() == 0
                ? required
                : new Quantity(required.value().multiply(BigDecimal.ONE.add(allowancePercent.movePointLeft(2))),
                        required.unit());
        Quantity procurement = required == null || catalog.getItemType() != CatalogItemType.MATERIAL ? null
                : work.settlementMode()
                == com.buildassist.calculation.SettlementMode.CUSTOMER_SUPPLIED_MATERIAL
                ? new Quantity(BigDecimal.ZERO, required.unit()) : adjusted;
        List<DraftQuoteSnapshot.RequirementSnapshot> requirements = new ArrayList<>();
        for (ChargeComponent component : components) {
            BigDecimal unitCost = component == ChargeComponent.MATERIAL
                    ? catalog.getNetProcurementUnitCost()
                    : component == ChargeComponent.LABOR ? catalog.getEstimatedOwnLaborUnitCost() : null;
            Quantity charged = required == null ? null
                    : chargeable && component == ChargeComponent.MATERIAL ? adjusted
                    : chargeable ? required : new Quantity(BigDecimal.ZERO, required.unit());
            requirements.add(new DraftQuoteSnapshot.RequirementSnapshot(
                    "req-" + lineId + "-" + component.name().toLowerCase(Locale.ROOT),
                    component, required, component == ChargeComponent.MATERIAL ? adjusted : null,
                    component == ChargeComponent.MATERIAL ? procurement : null,
                    charged, unitCost, null));
        }
        DraftQuoteSnapshot.CatalogItemSnapshot catalogSnapshot = new DraftQuoteSnapshot.CatalogItemSnapshot(
                catalog.getName(), catalog.getUnit(), catalog.getItemType(), catalog.getNetSellingUnitPrice(),
                catalog.getNetProcurementUnitCost(), catalog.getEstimatedOwnLaborUnitCost(), config);
        QuoteLineSnapshot newLine = new QuoteLineSnapshot(
                lineId, work.workId(), catalog.getId(), catalogSnapshot, work.settlementMode(),
                requirements, plan, result, required, procurement, required, null,
                false, null, "Munkához rendelt katalógustétel: " + unmapped.unmappedId(),
                null, chargeable, null, unmapped.assumptions(), null, null);
        List<DraftQuoteSnapshot.UnmappedLineSnapshot> unmappedLines = work.unmappedLines().stream()
                .filter(item -> !Objects.equals(item.unmappedId(), unmapped.unmappedId()))
                .toList();
        DraftQuoteSnapshot.QuoteWorkSnapshot updatedWork = new DraftQuoteSnapshot.QuoteWorkSnapshot(
                work.workId(), work.originalRequest(), work.source(), work.customerRequestedQuantity(),
                work.customerRequestedUnit(), work.settlementMode(),
                unmappedLines.isEmpty() ? WorkRecognitionStatus.MAPPED : WorkRecognitionStatus.PARTIALLY_MAPPED,
                catalog.getId(), work.sourceEvidence(), work.explanation(), work.explicitlyRequested(),
                work.assumptions(), work.missingParameters(), unmappedLines);
        List<DraftQuoteSnapshot.QuoteWorkSnapshot> updatedWorks = snapshot.workItems().stream()
                .map(item -> item.workId().equals(work.workId()) ? updatedWork : item)
                .toList();
        List<QuoteLineSnapshot> updatedLines = new ArrayList<>(snapshot.lineItems());
        updatedLines.add(newLine);
        DraftQuotePricingService.RepricingResult repriced = pricingService.reprice(
                new DraftQuoteSnapshot(snapshot.schemaVersion(), null, updatedWorks, updatedLines));
        snapshotService.save(draft, repriced.snapshot());
        draft.setDraftBody("");
        draft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
        emailDraftRepository.save(draft);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional(readOnly = true)
    public DraftDtos.WorkMappingPreviewResponse previewUnmappedWorkMapping(
            Long draftId, String workId, Long catalogItemId, String unmappedId, Long userId) {
        EmailDraft draft = findAuthorizedDraft(draftId, userId, false);
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        QuoteWorkSnapshot work = snapshot.workItems().stream()
                .filter(item -> Objects.equals(item.workId(), workId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("A kiválasztott munkafázis nem található."));
        DraftQuoteSnapshot.UnmappedLineSnapshot unmapped = work.unmappedLines().stream()
                .filter(item -> unmappedId == null || Objects.equals(item.unmappedId(), unmappedId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("A fel nem térképezett tétel nem található."));
        CatalogItem catalog = findTenantCatalogItem(userId, catalogItemId);
        CatalogCalculationConfig config = readCatalogConfig(catalog);
        CalculationPlan plan = mappingPlan(work, unmapped, catalog, config, null,
                draft.getEmail() == null ? null : draft.getEmail().getBodyText(), draft.getAdditionalNotes());
        if (plan == null) {
            return new DraftDtos.WorkMappingPreviewResponse(
                    workId, unmapped.unmappedId(), catalogItemId, catalog.getName(), catalog.getUnit(),
                    List.of(), CalculationStatus.NEEDS_INPUT,
                    List.of(new com.buildassist.calculation.CalculationIssue(
                            "PLAN_REQUIRED", "Ehhez a katalógustételhez nincs biztonságos számítási terv.", null)));
        }
        CalculationResult result = new com.buildassist.calculation.DeterministicQuantityCalculator()
                .calculate(plan);
        List<DraftDtos.MappingParameterDescriptor> parameters = plan.inputs().stream()
                .map(input -> {
                    String displayUnit = mappingDisplayUnit(input.id(), input.unit());
                    BigDecimal displayValue = input.value() == null ? null
                            : new Quantity(input.value(), QuantityUnit.fromCode(input.unit()))
                            .convertTo(QuantityUnit.fromCode(displayUnit)).value();
                    return new DraftDtos.MappingParameterDescriptor(
                            input.id(), mappingParameterLabel(input.id()), input.unit(), displayUnit,
                            input.value(), displayValue,
                            input.value() == null || (config.parameterConstraints().containsKey(input.id())
                                    && config.parameterConstraints().get(input.id()).required()),
                            input.source().name(), true);
                })
                .toList();
        return new DraftDtos.WorkMappingPreviewResponse(
                workId, unmapped.unmappedId(), catalogItemId, catalog.getName(), catalog.getUnit(),
                parameters, result.status(), result.issues());
    }

    private CatalogItem findTenantCatalogItem(Long userId, Long catalogItemId) {
        return catalogItemRepository.findAllByUserId(userId).stream()
                .filter(item -> catalogItemId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A katalógustétel nem található."));
    }

    private CalculationPlan mappingPlan(
            QuoteWorkSnapshot work,
            DraftQuoteSnapshot.UnmappedLineSnapshot unmapped,
            CatalogItem catalog,
            CatalogCalculationConfig config,
            DraftDtos.AddCatalogLineRequest request,
            String emailBody,
            String additionalNotes) {
        List<DraftDtos.MappingParameterValue> submitted = request == null
                ? List.of() : request.parameters();
        Map<String, DraftDtos.MappingParameterValue> submittedById = new HashMap<>();
        for (DraftDtos.MappingParameterValue parameter : submitted) {
            if (parameter == null || parameter.id() == null || parameter.value() == null
                    || submittedById.putIfAbsent(parameter.id(), parameter) != null) {
                throw new IllegalArgumentException("A megadott számítási paraméter hiányzik vagy duplikált.");
            }
        }
        CalculationPlan suggested = unmapped.suggestedCalculationPlan();
        if (suggested == null) {
            Quantity directQuantity = request == null || request.quantity() == null
                    ? work.customerRequestedQuantity()
                    : new Quantity(request.quantity(), QuantityUnit.fromCode(
                            request.unit() == null ? catalog.getUnit() : request.unit()));
            if (allowed(config).contains(CalculationOperation.DIRECT)
                    && (directQuantity == null || compatible(directQuantity, catalog.getUnit()))) {
                String unit = catalog.getUnit();
                CalculationInput input = new CalculationInput(
                        "mappingQuantity", directQuantity == null ? null
                        : directQuantity.convertTo(QuantityUnit.fromCode(unit)).value(), unit,
                        directQuantity == null ? InputSource.AI_ASSUMPTION
                                : request != null && request.quantity() != null
                                ? InputSource.USER_OVERRIDE : work.source(),
                        directQuantity != null && (request != null && request.quantity() != null
                                || work.source() != InputSource.AI_ASSUMPTION),
                        request != null && request.quantity() != null
                        ? "Kivitelező által megadott mennyiség" : work.sourceEvidence());
                suggested = new CalculationPlan(List.of(input), List.of(new CalculationStep(
                        "mappingDirect", CalculationOperation.DIRECT, List.of(input.id()), unit, null, null)),
                        "mappingDirect", List.of());
            } else if (work.customerRequestedQuantity() != null
                    && "m2".equals(work.customerRequestedUnit()) && "m3".equals(catalog.getUnit())
                    && allowed(config).contains(CalculationOperation.VOLUME)) {
                String thicknessId = config.parameterConstraints().entrySet().stream()
                        .filter(entry -> entry.getValue().required())
                        .filter(entry -> isLengthUnit(entry.getValue().unit()))
                        .map(Map.Entry::getKey).findFirst().orElse("thickness");
                String thicknessUnit = config.parameterConstraints().containsKey(thicknessId)
                        ? config.parameterConstraints().get(thicknessId).unit() : "m";
                List<CalculationInput> inputs = new ArrayList<>();
                inputs.add(new CalculationInput("area", work.customerRequestedQuantity().value(),
                        work.customerRequestedUnit(), work.source(),
                        work.source() != InputSource.AI_ASSUMPTION, work.sourceEvidence()));
                CatalogParameterDefault configured = config.defaultParameters().get(thicknessId);
                inputs.add(configured == null
                        ? new CalculationInput(thicknessId, null, thicknessUnit, InputSource.AI_ASSUMPTION, false)
                        : new CalculationInput(thicknessId, configured.value(), configured.unit(),
                                configured.source(), true));
                suggested = new CalculationPlan(inputs, List.of(new CalculationStep(
                        "mappingVolume", CalculationOperation.VOLUME,
                        List.of("area", thicknessId), catalog.getUnit(), null, null)),
                        "mappingVolume", List.of());
            }
        }
        if (suggested == null) {
            if (!submittedById.isEmpty()) {
                throw new IllegalArgumentException("A megadott paraméter nem tartozik ehhez a katalógustételhez.");
            }
            return null;
        }

        Set<CalculationOperation> allowed = allowed(config);
        if (suggested.steps().stream().anyMatch(step -> step.operation() == null
                || !allowed.contains(step.operation()))) {
            throw new IllegalArgumentException("A számítási terv olyan műveletet tartalmaz, amelyet a katalógus nem engedélyez.");
        }
        Map<String, CalculationInput> knownInputs = new HashMap<>();
        List<CalculationInput> inputs = new ArrayList<>();
        for (CalculationInput original : suggested.inputs()) {
            CatalogParameterDefault configured = config.defaultParameters().get(original.id());
            CatalogParameterConstraint constraint = config.parameterConstraints().get(original.id());
            BigDecimal value = original.value();
            InputSource source = original.source();
            boolean approved = original.approved();
            String evidence = original.evidence();
            if (source == InputSource.CATALOG_DEFAULT || source == InputSource.CONTRACTOR_PROFILE
                    || source == InputSource.USER_OVERRIDE) {
                value = null;
                source = InputSource.AI_ASSUMPTION;
                approved = false;
                evidence = null;
            }
            boolean useConfiguredDefault = configured != null
                    && (value == null || source == InputSource.AI_ASSUMPTION);
            if (useConfiguredDefault) {
                value = configured.value();
                source = configured.source();
                approved = true;
                evidence = null;
            }
            if (source == InputSource.CUSTOMER_EMAIL || source == InputSource.CUSTOMER_SUPPLEMENT) {
                String sourceText = source == InputSource.CUSTOMER_EMAIL ? emailBody : additionalNotes;
                if (value != null && (evidence == null || sourceText == null || !sourceText.contains(evidence))) {
                    value = null;
                    source = InputSource.AI_ASSUMPTION;
                    approved = false;
                    evidence = null;
                }
            }
            String unit = useConfiguredDefault ? configured.unit() : original.unit();
            if (submittedById.containsKey(original.id())) {
                DraftDtos.MappingParameterValue userValue = submittedById.remove(original.id());
                Quantity converted = new Quantity(userValue.value(), QuantityUnit.fromCode(
                        userValue.unit() == null ? unit : userValue.unit()))
                        .convertTo(QuantityUnit.fromCode(unit));
                value = converted.value();
                source = InputSource.USER_OVERRIDE;
                approved = true;
                evidence = "Kivitelező által megadott érték";
            }
            if (constraint != null && !unit.equals(constraint.unit())) {
                new Quantity(BigDecimal.ONE, QuantityUnit.fromCode(unit))
                        .convertTo(QuantityUnit.fromCode(constraint.unit()));
            }
            if (value != null) {
                Quantity checked = new Quantity(value, QuantityUnit.fromCode(unit));
                if (constraint != null) {
                    Quantity converted = checked.convertTo(QuantityUnit.fromCode(constraint.unit()));
                    if ((constraint.minimum() != null && converted.value().compareTo(constraint.minimum()) < 0)
                            || (constraint.maximum() != null && converted.value().compareTo(constraint.maximum()) > 0)) {
                        throw new IllegalArgumentException("A megadott paraméter kívül esik a katalógusban rögzített tartományon.");
                    }
                }
            }
            CalculationInput input = new CalculationInput(
                    original.id(), value, unit, source, approved, evidence);
            inputs.add(input);
            knownInputs.put(input.id(), input);
        }
        for (Map.Entry<String, CatalogParameterConstraint> entry : config.parameterConstraints().entrySet()) {
            if (!knownInputs.containsKey(entry.getKey()) && entry.getValue().required()) {
                CatalogParameterDefault configured = config.defaultParameters().get(entry.getKey());
                String unit = configured == null ? entry.getValue().unit() : configured.unit();
                BigDecimal value = configured == null ? null : configured.value();
                InputSource source = configured == null ? InputSource.AI_ASSUMPTION : configured.source();
                boolean approved = configured != null;
                String evidence = null;
                DraftDtos.MappingParameterValue submittedValue = submittedById.remove(entry.getKey());
                if (submittedValue != null) {
                    value = new Quantity(submittedValue.value(), QuantityUnit.fromCode(
                            submittedValue.unit() == null ? unit : submittedValue.unit()))
                            .convertTo(QuantityUnit.fromCode(unit)).value();
                    source = InputSource.USER_OVERRIDE;
                    approved = true;
                    evidence = "Kivitelező által megadott érték";
                }
                if (value != null) {
                    Quantity converted = new Quantity(value, QuantityUnit.fromCode(unit))
                            .convertTo(QuantityUnit.fromCode(entry.getValue().unit()));
                    if ((entry.getValue().minimum() != null
                            && converted.value().compareTo(entry.getValue().minimum()) < 0)
                            || (entry.getValue().maximum() != null
                            && converted.value().compareTo(entry.getValue().maximum()) > 0)) {
                        throw new IllegalArgumentException(
                                "A megadott paraméter kívül esik a katalógusban rögzített tartományon.");
                    }
                }
                CalculationInput input = new CalculationInput(
                        entry.getKey(), value, unit, source, approved, evidence);
                inputs.add(input);
                knownInputs.put(input.id(), input);
            }
        }
        if (!submittedById.isEmpty()) {
            throw new IllegalArgumentException("Ismeretlen számítási paraméter.");
        }
        List<String> reviewReasons = suggested.reviewReasons().stream()
                .filter(reason -> !reason.startsWith("AI-javaslat,"))
                .toList();
        return new CalculationPlan(inputs, suggested.steps(), suggested.outputReference(), reviewReasons);
    }

    private Set<CalculationOperation> allowed(CatalogCalculationConfig config) {
        return config.allowedCalculations().isEmpty()
                ? Set.of(CalculationOperation.DIRECT) : config.allowedCalculations();
    }

    private boolean compatible(Quantity quantity, String unit) {
        try {
            quantity.convertTo(QuantityUnit.fromCode(unit));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isLengthUnit(String unit) {
        return Set.of("mm", "cm", "m", "fm", "folyóméter", "folyometer")
                .contains(unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT));
    }

    private String mappingParameterLabel(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "area", "surface", "surfacearea" -> "Terület";
            case "thickness", "layerthickness" -> "Rétegvastagság";
            case "allowance", "materialallowance", "miraallowancepercent" -> "Ráhagyás";
            case "coats", "layers" -> "Rétegszám";
            case "coverage", "coveragerate" -> "Fedőképesség";
            case "length", "cablelength" -> "Hosszúság";
            case "width" -> "Szélesség";
            case "height" -> "Magasság";
            case "count", "quantity", "mappingquantity" -> "Mennyiség";
            default -> "Kiegészítő méret vagy mennyiség";
        };
    }

    private String mappingDisplayUnit(String id, String unit) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "thickness", "layerthickness" -> "cm";
            default -> unit;
        };
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse addManualLine(
            Long draftId, DraftDtos.AddManualLineRequest request, Long userId) {
        validateManualLine(request.name(), request.quantity(), request.unit(),
                request.netUnitPrice(), request.itemType());
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, request.expectedVersion());
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        QuoteWorkSnapshot work;
        List<QuoteWorkSnapshot> works = new ArrayList<>(snapshot.workItems());
        if (request.workId() == null || request.workId().isBlank()) {
            String workId = new com.buildassist.calculation.QuoteIdGenerator().newWorkId();
            work = new QuoteWorkSnapshot(workId, request.name().trim(), InputSource.USER_OVERRIDE,
                    new Quantity(request.quantity(), QuantityUnit.fromCode(request.unit())),
                    request.unit(), com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR,
                    WorkRecognitionStatus.MAPPED, null, null, "Kézzel hozzáadott ajánlati tétel.",
                    true, List.of(), List.of(), List.of());
            works.add(work);
        } else {
            work = works.stream().filter(item -> item.workId().equals(request.workId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("A kapcsolódó munkafázis nem található."));
        }
        QuoteLineSnapshot line = createManualLine(
                new com.buildassist.calculation.QuoteIdGenerator().newLineItemId(),
                work, request.name(), request.quantity(), request.unit(),
                request.netUnitPrice(), request.itemType());
        List<QuoteLineSnapshot> lines = new ArrayList<>(snapshot.lineItems());
        lines.add(line);
        persistSnapshotEdit(draft, snapshot, works, lines);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse updateManualLine(
            Long draftId, String lineItemId, DraftDtos.UpdateManualLineRequest request, Long userId) {
        validateManualLine(request.name(), request.quantity(), request.unit(),
                request.netUnitPrice(), request.itemType());
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, request.expectedVersion());
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        QuoteLineSnapshot existing = snapshot.lineItems().stream()
                .filter(line -> line.lineItemId().equals(lineItemId) && line.userOverrideLine())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("A kézi ajánlati sor nem található."));
        QuoteWorkSnapshot work = snapshot.workItems().stream()
                .filter(item -> item.workId().equals(existing.workId())).findFirst().orElseThrow();
        QuoteLineSnapshot updated = createManualLine(
                existing.lineItemId(), work, request.name(), request.quantity(),
                request.unit(), request.netUnitPrice(), request.itemType());
        List<QuoteLineSnapshot> lines = snapshot.lineItems().stream()
                .map(line -> line.lineItemId().equals(lineItemId) ? updated : line).toList();
        List<QuoteWorkSnapshot> works = snapshot.workItems().stream()
                .map(item -> item.workId().equals(work.workId())
                        && item.source() == InputSource.USER_OVERRIDE
                        ? new QuoteWorkSnapshot(item.workId(), request.name().trim(), item.source(),
                                new Quantity(request.quantity(), QuantityUnit.fromCode(request.unit())),
                                request.unit(), item.settlementMode(), item.recognitionStatus(),
                                item.suggestedCatalogItemId(), item.sourceEvidence(), item.explanation(),
                                item.explicitlyRequested(), item.assumptions(), item.missingParameters(),
                                item.unmappedLines())
                        : item).toList();
        persistSnapshotEdit(draft, snapshot, works, lines);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse deleteManualLine(
            Long draftId, String lineItemId, Long expectedVersion, Long userId) {
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, expectedVersion);
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        QuoteLineSnapshot line = snapshot.lineItems().stream()
                .filter(item -> item.lineItemId().equals(lineItemId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Az ajánlati sor nem található."));
        List<QuoteLineSnapshot> lines = snapshot.lineItems().stream()
                .filter(item -> !item.lineItemId().equals(lineItemId)).toList();
        List<QuoteWorkSnapshot> works = snapshot.workItems();
        boolean syntheticWork = works.stream().anyMatch(work ->
                work.workId().equals(line.workId()) && work.source() == InputSource.USER_OVERRIDE
                        && lines.stream().noneMatch(remaining -> remaining.workId().equals(work.workId())));
        if (syntheticWork) {
            works = works.stream().filter(work -> !work.workId().equals(line.workId())).toList();
        } else if (lines.stream().noneMatch(remaining -> remaining.workId().equals(line.workId()))) {
            works = works.stream().map(work -> work.workId().equals(line.workId())
                    ? new QuoteWorkSnapshot(work.workId(), work.originalRequest(), work.source(),
                            work.customerRequestedQuantity(), work.customerRequestedUnit(),
                            work.settlementMode(), WorkRecognitionStatus.UNMAPPED,
                            work.suggestedCatalogItemId(), work.sourceEvidence(), work.explanation(),
                            work.explicitlyRequested(), work.assumptions(), work.missingParameters(),
                            work.unmappedLines())
                    : work).toList();
        }
        persistSnapshotEdit(draft, snapshot, works, lines);
        List<CoverageDecision> decisions = quoteCoverageService.readDecisions(draft.getCoverageDecisionsJson());
        decisions.removeIf(decision -> decision.issueId().equals(line.workId()));
        draft.setCoverageDecisionsJson(writeJson(decisions));
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    private void validateManualLine(
            String name, BigDecimal quantity, String unit, BigDecimal netUnitPrice,
            CatalogItemType itemType) {
        if (name == null || name.isBlank() || name.trim().length() > 200
                || quantity == null || quantity.signum() <= 0
                || netUnitPrice == null || netUnitPrice.signum() <= 0 || itemType == null) {
            throw new IllegalArgumentException("A kézi sorhoz megnevezés, pozitív mennyiség, egység, ár és tételtípus szükséges.");
        }
        QuantityUnit.fromCode(unit);
    }

    private QuoteLineSnapshot createManualLine(
            String lineId, QuoteWorkSnapshot work, String name, BigDecimal amount,
            String unit, BigDecimal netUnitPrice, CatalogItemType itemType) {
        Quantity quantity = new Quantity(amount, QuantityUnit.fromCode(unit));
        Quantity normalized = quantity.convertTo(QuantityUnit.fromCode(unit));
        if (itemType == CatalogItemType.MATERIAL
                && work.settlementMode() != com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR) {
            throw new IllegalArgumentException("Az ügyfél által biztosított vagy csak munkadíjas anyag nem számlázható fel.");
        }
        List<ChargeComponent> components = switch (itemType) {
            case MATERIAL -> List.of(ChargeComponent.MATERIAL);
            case LABOR -> List.of(ChargeComponent.LABOR);
            case COMBINED -> work.settlementMode()
                    == com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR
                    ? List.of(ChargeComponent.MATERIAL, ChargeComponent.LABOR)
                    : List.of(ChargeComponent.LABOR);
            case OTHER -> List.of(ChargeComponent.OTHER);
        };
        String inputId = "manualQuantity";
        CalculationPlan plan = new CalculationPlan(
                List.of(new CalculationInput(inputId, normalized.value(), normalized.unit().code(),
                        InputSource.USER_OVERRIDE, true, "Kézi ajánlati mennyiség")),
                List.of(new CalculationStep("manualDirect", CalculationOperation.DIRECT,
                        List.of(inputId), normalized.unit().code(), null, null)),
                "manualDirect", List.of());
        CalculationResult result = new com.buildassist.calculation.DeterministicQuantityCalculator().calculate(plan);
        List<DraftQuoteSnapshot.RequirementSnapshot> requirements = new ArrayList<>();
        for (ChargeComponent component : components) {
            String requirementId = "req-" + lineId + "-" + component.name().toLowerCase(Locale.ROOT);
            requirements.add(new DraftQuoteSnapshot.RequirementSnapshot(
                    requirementId, component, normalized,
                    component == ChargeComponent.MATERIAL ? normalized : null,
                    component == ChargeComponent.MATERIAL ? normalized : null,
                    normalized, null, null));
        }
        CatalogCalculationConfig noCatalogConfig = CatalogCalculationConfig.empty();
        DraftQuoteSnapshot.CatalogItemSnapshot descriptor = new DraftQuoteSnapshot.CatalogItemSnapshot(
                name.trim(), normalized.unit().code(), itemType, netUnitPrice,
                null, null, noCatalogConfig);
        return new QuoteLineSnapshot(
                lineId, work.workId(), null, descriptor, work.settlementMode(), requirements,
                plan, result, normalized,
                itemType == CatalogItemType.MATERIAL ? normalized : null,
                normalized, null, false, null,
                "Kézi, egyszeri ajánlati tétel.", null, true, null,
                List.of(), null, null, netUnitPrice,
                "USER_OVERRIDE: kézzel megadott ajánlati ár", true);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse updateCataloglessManualLine(
            Long draftId, String lineItemId, DraftDtos.UpdateManualLineRequest request, Long userId) {
        return updateManualLine(draftId, lineItemId, request, userId);
    }

    private void persistSnapshotEdit(
            EmailDraft draft, DraftQuoteSnapshot original,
            List<QuoteWorkSnapshot> works, List<QuoteLineSnapshot> lines) {
        DraftQuotePricingService.RepricingResult repriced = pricingService.reprice(
                new DraftQuoteSnapshot(original.schemaVersion(), null, works, lines));
        snapshotService.save(draft, repriced.snapshot());
        draft.setDraftBody("");
        draft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
        emailDraftRepository.save(draft);
    }

    private CatalogCalculationConfig readCatalogConfig(CatalogItem catalog) {
        if (catalog.getCalculationConfigJson() == null || catalog.getCalculationConfigJson().isBlank()) {
            return CatalogCalculationConfig.empty();
        }
        try {
            CatalogCalculationConfig config = objectMapper.readValue(
                    catalog.getCalculationConfigJson(), CatalogCalculationConfig.class);
            List<String> errors = new com.buildassist.calculation.CatalogCalculationConfigValidator()
                    .validate(config);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("A katalógustétel kalkulációs beállítása érvénytelen.");
            }
            return config;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("A katalógustétel kalkulációs beállítása érvénytelen.", exception);
        }
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse previewDraft(
            Long draftId, DraftDtos.FinalizeDraftRequest request, Long userId) {
        if (request == null) {
            throw new IllegalArgumentException("Preview request is required.");
        }
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, request.expectedVersion());
        applyDraftChanges(draft, new DraftDtos.SaveDraftRequest(
                request.clientName(), request.lineItems(), request.additionalNotes(),
                request.includeAdditionalNotes(), request.expectedVersion()), userId);
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        ensurePreviewable(snapshot, draft, userId);
        String body = renderDraftHtml(draft.getId(), snapshot, draft, userId);
        draft.setDraftBody(body);
        emailDraftRepository.save(draft);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse finalizeDraft(
            Long draftId, DraftDtos.FinalizeDraftRequest request, Long userId) {
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        if (draft.getStatus() == EmailDraft.DraftStatus.READY) {
            return toResponse(draft, userId);
        }
        if (request != null) {
            requireExpectedVersion(draft, request.expectedVersion());
            applyDraftChanges(draft, new DraftDtos.SaveDraftRequest(
                    request.clientName(), request.lineItems(), request.additionalNotes(),
                    request.includeAdditionalNotes(), request.expectedVersion()), userId);
        } else {
            throw new IllegalArgumentException("Az ajánlat aktuális verziója kötelező a véglegesítéshez.");
        }

        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        CoverageResponse coverage = coverageFor(draft);
        if (request != null && request.confirmedFingerprint() != null
                && !Objects.equals(request.confirmedFingerprint(), coverage.quoteFingerprint())) {
            throw new IllegalArgumentException("Az ajánlat megváltozott; ellenőrizd újra.");
        }
        ensurePreviewable(snapshot, draft, userId);
        List<String> unacknowledgedWarnings = coverage.warnings().stream()
                .filter(warning -> !warning.acknowledged())
                .map(CoverageWarning::text)
                .toList();
        if (!unacknowledgedWarnings.isEmpty()
                && (request == null
                || !Boolean.TRUE.equals(request.confirmIncomplete())
                || !Objects.equals(request.confirmedFingerprint(), coverage.quoteFingerprint()))) {
            throw new IllegalArgumentException(
                    "Az ajánlat figyelmeztetéseket tartalmaz; a Gmail-piszkozat véglegesítése szükséges.");
        }

        String body = renderDraftHtml(draftId, snapshot, draft, userId);
        draft.setDraftBody(body);
        try {
            CompanyProfile profile = companyProfileRepository.findByUserId(userId).orElse(null);
            byte[] pdf = quotePdfService.render(body);
            saveDraftToGmail(draft.getEmail(), pdf, "arajanlat-" + draftId + ".pdf",
                    draft.getClientName(), profile, userId);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create Gmail draft with PDF quote.", exception);
        }

        draft.setStatus(EmailDraft.DraftStatus.READY);
        emailDraftRepository.save(draft);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional
    public byte[] generatePdf(Long draftId, Long userId) {
        EmailDraft draft = findAuthorizedDraft(draftId, userId, false);
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        ensurePreviewable(snapshot, draft, userId);
        String body = renderDraftHtml(draftId, snapshot, draft, userId);
        draft.setDraftBody(body);
        emailDraftRepository.save(draft);
        return quotePdfService.render(body);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse updateCoverageDecision(
            Long draftId, String issueId, CoverageDecisionRequest request, Long userId) {
        if (request == null || request.status() == null || request.draft() == null) {
            throw new IllegalArgumentException("A döntés és az aktuális ajánlati adatok megadása kötelező.");
        }
        EmailDraft draft = findAuthorizedDraft(draftId, userId, true);
        requireExpectedVersion(draft, request.draft().expectedVersion());
        applyDraftChanges(draft, request.draft(), userId);
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);

        boolean isWork = snapshot.workItems().stream().anyMatch(work -> work.workId().equals(issueId));
        if (isWork) {
            List<CoverageDecision> decisions =
                    quoteCoverageService.readDecisions(draft.getCoverageDecisionsJson());
            List<CoverageDecision> updated = quoteCoverageService.setDecision(
                    decisions, issueId, request.status(), request.lineItemId(), snapshot);
            draft.setCoverageDecisionsJson(writeJson(updated));
        } else {
            Set<String> acknowledged = new HashSet<>(
                    quoteCoverageService.readStringList(draft.getAcknowledgedWarningsJson()));
            boolean warningExists = coverageWarnings(draft, snapshot).stream()
                    .anyMatch(warning -> warning.warningId().equals(issueId));
            if (!warningExists) {
                throw new IllegalArgumentException("Az ellenőrzési elem nem található ennél az ajánlatnál.");
            }
            if (request.status() == CoverageStatus.INCLUDED) {
                acknowledged.add(issueId);
            } else if (request.status() == CoverageStatus.NEEDS_REVIEW) {
                acknowledged.remove(issueId);
            } else {
                throw new IllegalArgumentException("Érvénytelen AI-figyelmeztetés állapot.");
            }
            draft.setAcknowledgedWarningsJson(writeJson(acknowledged));
        }
        emailDraftRepository.save(draft);
        emailDraftRepository.flush();
        return toResponse(draft, userId);
    }

    @Transactional(readOnly = true)
    public CoverageResponse getCoverage(Long draftId, Long userId) {
        EmailDraft draft = findAuthorizedDraft(draftId, userId, false);
        return coverageFor(draft);
    }

    @Transactional(readOnly = true)
    public DraftDtos.GenerateDraftResponse responseForEmail(EmailDraft draft, Long userId) {
        return toResponse(draft, userId);
    }

    private void applyDraftChanges(
            EmailDraft draft, DraftDtos.SaveDraftRequest request, Long userId) {
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        DraftQuoteSnapshot updatedSnapshot = applySubmittedLineItems(
                snapshot, request.lineItems(), draft, userId);
        boolean snapshotChanged = updatedSnapshot != snapshot;
        if (snapshotChanged) {
            DraftQuotePricingService.RepricingResult repriced = pricingService.reprice(updatedSnapshot);
            snapshot = repriced.snapshot();
            snapshotService.save(draft, snapshot);
        }

        Email email = draft.getEmail();
        String clientName = request.clientName() != null && !request.clientName().isBlank()
                ? request.clientName().trim()
                : draft.getClientName() != null ? draft.getClientName()
                : email.getFromAddress() != null ? email.getFromAddress() : "Érdeklődő";
        String additionalNotes = request.additionalNotes() == null
                ? draft.getAdditionalNotes() : normalizeOptional(request.additionalNotes());
        boolean includeAdditionalNotes = request.includeAdditionalNotes() == null
                ? draft.isIncludeAdditionalNotes() : request.includeAdditionalNotes();
        boolean metadataChanged = !Objects.equals(clientName, draft.getClientName())
                || !Objects.equals(additionalNotes, draft.getAdditionalNotes())
                || includeAdditionalNotes != draft.isIncludeAdditionalNotes();

        draft.setClientName(clientName);
        draft.setAdditionalNotes(additionalNotes);
        draft.setIncludeAdditionalNotes(includeAdditionalNotes);
        if (snapshotChanged || metadataChanged) {
            draft.setDraftBody("");
            if (draft.getStatus() != EmailDraft.DraftStatus.SENT) {
                draft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
            }
            if (metadataChanged) {
                draft.setAcknowledgedWarningsJson("[]");
            }
        }
    }

    private DraftQuoteSnapshot applySubmittedLineItems(
            DraftQuoteSnapshot snapshot,
            List<DraftLineItem> submitted,
            EmailDraft draft,
            Long userId) {
        if (submitted == null) {
            return snapshot;
        }
        List<DraftLineItem> expected = lineItems(snapshot, draft, userId);
        if (submitted.size() != expected.size()) {
            throw new IllegalArgumentException(
                    "Az ajánlati sorok nem módosíthatók vagy hozhatók létre a régi sor-API-n keresztül.");
        }
        Map<String, DraftLineItem> expectedById = new HashMap<>();
        expected.forEach(item -> expectedById.put(item.lineItemId(), item));
        Set<String> seen = new HashSet<>();
        Map<String, DraftLineItem> submittedItems = new HashMap<>();

        for (DraftLineItem item : submitted) {
            if (item == null || item.lineItemId() == null || !seen.add(item.lineItemId())) {
                throw new IllegalArgumentException("Az ajánlati sorazonosítóknak jelen kell lenniük és egyedinek kell lenniük.");
            }
            DraftLineItem serverLine = expectedById.get(item.lineItemId());
            QuoteLineSnapshot frozenLine = snapshot.lineItems().stream()
                    .filter(line -> item != null && Objects.equals(item.lineItemId(), line.lineItemId()))
                    .findFirst().orElse(null);
            if (serverLine == null || frozenLine == null
                    || !Objects.equals(item.name(), serverLine.name())
                    || !Objects.equals(item.unit(), serverLine.unit())
                    || !Objects.equals(item.catalogItemId(), serverLine.catalogItemId())
                    || !"NET".equals(item.priceBasis())
                    || (frozenLine.userOverrideLine()
                    ? item.catalogUnitPrice() != null
                    : item.catalogUnitPrice() == null
                    || item.catalogUnitPrice().compareTo(frozenLine.catalogItem().netSellingUnitPrice()) != 0)
                    || item.unitPrice() == null
                    || (!frozenLine.userOverrideLine() && !item.priceOverridden()
                    && item.unitPrice().compareTo(frozenLine.catalogItem().netSellingUnitPrice()) != 0)
                    || ((item.priceOverridden() || frozenLine.userOverrideLine())
                    && (item.unitPrice().signum() <= 0
                    || item.priceOverrideReason() == null || item.priceOverrideReason().isBlank()))) {
                throw new IllegalArgumentException(
                        "A katalógus adatai szerveroldaliak; kézi árhoz pozitív összeg és indoklás szükséges.");
            }
            if (item.quantity() != null && item.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Az ajánlati mennyiségnek pozitívnak kell lennie.");
            }
            if (item.quantity() == null && serverLine.quantity() != null) {
                throw new IllegalArgumentException("A beárazott ajánlati mennyiség nem hagyható üresen.");
            }
            submittedItems.put(item.lineItemId(), item);
        }
        if (!seen.equals(expectedById.keySet())) {
            throw new IllegalArgumentException("Az ajánlati sorok nem hagyhatók el a lefedettségi döntés helyett.");
        }

        List<QuoteLineSnapshot> updatedLines = new ArrayList<>();
        boolean changed = false;
        for (QuoteLineSnapshot line : snapshot.lineItems()) {
            DraftLineItem submittedItem = submittedItems.get(line.lineItemId());
            if (submittedItem == null) {
                updatedLines.add(line);
                continue;
            }
            QuoteLineSnapshot editedLine = line;
            boolean parametersChanged = !sameCalculationParameters(line, submittedItem);
            if (parametersChanged) {
                editedLine = lineEditor.apply(
                        line, submittedItem.calculationParameters(), submittedItem.assumptionsApproved());
                if (editedLine != line) {
                    changed = true;
                }
            }
            Quantity storedOverride = editedLine.userOverrideQuantity();
            boolean quantityChanged = submittedItem.quantity() != null
                    && (!Objects.equals(submittedItem.quantity(), expectedById.get(line.lineItemId()).quantity())
                    || !parametersChanged);
            if (parametersChanged && !quantityChanged) {
                storedOverride = null;
            } else if (quantityChanged && editedLine.customerChargeQuantity() != null) {
                Quantity override;
                try {
                    override = new Quantity(submittedItem.quantity(),
                            QuantityUnit.fromCode(editedLine.catalogItem().unit()));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("A kézi mennyiség egysége vagy értéke érvénytelen.", exception);
                }
                Quantity currentQuantity = editedLine.userOverrideQuantity() == null
                        ? editedLine.customerChargeQuantity() : editedLine.userOverrideQuantity();
                storedOverride = currentQuantity.convertTo(override.unit()).value()
                        .compareTo(override.value()) == 0 ? null
                        : override.convertTo(editedLine.customerChargeQuantity().unit());
            }
            BigDecimal requestedPriceOverride = submittedItem.priceOverridden()
                    ? submittedItem.unitPrice() : null;
            String requestedPriceReason = submittedItem.priceOverridden()
                    ? submittedItem.priceOverrideReason().trim() : null;
            boolean lineChanged = !Objects.equals(editedLine.userOverrideQuantity(), storedOverride)
                    || !Objects.equals(editedLine.manualNetUnitPrice(), requestedPriceOverride)
                    || !Objects.equals(editedLine.manualPriceReason(), requestedPriceReason);
            updatedLines.add(lineChanged
                    ? copyWithEdits(editedLine, storedOverride, requestedPriceOverride, requestedPriceReason)
                    : editedLine);
            changed |= lineChanged;
        }
        return changed
                ? new DraftQuoteSnapshot(snapshot.schemaVersion(), null, snapshot.workItems(), updatedLines)
                : snapshot;
    }

    private QuoteLineSnapshot copyWithEdits(
            QuoteLineSnapshot line,
            Quantity override,
            BigDecimal manualNetUnitPrice,
            String manualPriceReason) {
        return new QuoteLineSnapshot(
                line.lineItemId(), line.workId(), line.catalogItemId(), line.catalogItem(),
                line.settlementMode(), line.requirements(), line.calculationPlan(),
                line.calculationResult(), line.requiredQuantity(), line.procurementQuantity(),
                line.customerChargeQuantity(), override, line.assumptionsApproved(),
                line.approvedFingerprint(), line.explanation(), null, line.chargeable(),
                line.procurementPackageCount(), line.assumptions(),
                line.procurementOutputReference(), line.procurementPackageCountReference(),
                manualNetUnitPrice, manualPriceReason, line.userOverrideLine());
    }

    private boolean sameCalculationParameters(QuoteLineSnapshot line, DraftLineItem submitted) {
        List<com.buildassist.dto.AiDtos.CalculationParameter> actual =
                line.calculationPlan() == null ? List.of() : line.calculationPlan().inputs().stream()
                .map(input -> new com.buildassist.dto.AiDtos.CalculationParameter(
                        input.id(), input.value(), input.unit(), input.source().name(), input.approved(),
                        "USER_REJECTED_ASSUMPTION".equals(input.evidence())))
                .toList();
        return actual.equals(submitted.calculationParameters())
                && line.assumptionsApproved() == submitted.assumptionsApproved();
    }

    private DraftDtos.GenerateDraftResponse toResponse(EmailDraft draft, Long userId) {
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        CoverageResponse coverage = coverageFor(draft, snapshot);
        CalculationStatus status = overallStatus(snapshot, draft);
        return new DraftDtos.GenerateDraftResponse(
                draft.getId(),
                draft.getDraftBody(),
                unmappedRequests(snapshot),
                reviewWarnings(draft),
                lineItems(snapshot, draft, userId),
                draft.getClientName(),
                draft.getAdditionalNotes(),
                draft.isIncludeAdditionalNotes(),
                isVatRegistered(userId),
                coverage.workItems(),
                coverage.warnings(),
                coverage.analysisFresh(),
                draft.getStatus() == EmailDraft.DraftStatus.READY,
                snapshot,
                status,
                netQuotePrice(snapshot, draft),
                taxSummary(snapshot, draft, userId).taxRegime(),
                taxSummary(snapshot, draft, userId).vatRate(),
                taxSummary(snapshot, draft, userId).vatAmount(),
                taxSummary(snapshot, draft, userId).grossTotal(),
                taxSummary(snapshot, draft, userId).configurationValid(),
                draft.getVersion());
    }

    private CoverageResponse coverageFor(EmailDraft draft) {
        return coverageFor(draft, requiredSnapshot(draft));
    }

    private CoverageResponse coverageFor(EmailDraft draft, DraftQuoteSnapshot snapshot) {
        List<CoverageDecision> decisions = quoteCoverageService.readDecisions(draft.getCoverageDecisionsJson());
        List<String> acknowledged =
                quoteCoverageService.readStringList(draft.getAcknowledgedWarningsJson());
        Email email = draft.getEmail();
        boolean analysisFresh = draft.getAnalysisFingerprint() != null && Objects.equals(
                draft.getAnalysisFingerprint(),
                quoteCoverageService.analysisFingerprint(
                        email == null ? null : email.getBodyText(), draft.getAdditionalNotes()));
        return quoteCoverageService.response(
                snapshot, decisions, reviewWarnings(draft), acknowledged,
                analysisFresh, quoteCoverageService.quoteFingerprint(draft, snapshot));
    }

    private List<CoverageWarning> coverageWarnings(
            EmailDraft draft, DraftQuoteSnapshot snapshot) {
        List<String> warnings = new ArrayList<>(reviewWarnings(draft));
        for (QuoteLineSnapshot line : snapshot.lineItems()) {
            if (line.chargeable() && line.calculationResult().status() == CalculationStatus.VALID
                    && line.catalogItem() != null
                    && line.catalogItem().netSellingUnitPrice().signum() == 0) {
                warnings.add("Az ajánlat egyik sora 0 Ft nettó egységárú. Ellenőrizd, hogy ez tudatos-e.");
            }
        }
        Set<String> acknowledged = new HashSet<>(
                quoteCoverageService.readStringList(draft.getAcknowledgedWarningsJson()));
        return quoteCoverageService.warningItems(warnings, acknowledged);
    }

    private List<DraftLineItem> lineItems(
            DraftQuoteSnapshot snapshot, EmailDraft draft, Long userId) {
        Set<String> excludedWorks = excludedWorkIds(draft);
        return snapshot.lineItems().stream()
                .filter(line -> !excludedWorks.contains(line.workId()))
                .filter(QuoteLineSnapshot::chargeable)
                .filter(line -> line.catalogItem() != null)
                .map(line -> {
                    Quantity quantity = line.userOverrideQuantity() == null
                            ? line.customerChargeQuantity() : line.userOverrideQuantity();
                    BigDecimal netUnitPrice = NetMoney.of(line.manualNetUnitPrice() == null
                            ? line.catalogItem().netSellingUnitPrice() : line.manualNetUnitPrice()).huf();
                    BigDecimal subtotal = line.netLineTotal();
                    return new DraftLineItem(
                            line.catalogItem().name(),
                            line.catalogItem().unit(),
                            quantity == null ? null : quantity.value(),
                            netUnitPrice,
                            subtotal,
                            line.lineItemId(),
                            line.catalogItemId(),
                            "NET",
                            line.manualNetUnitPrice() != null,
                            line.manualPriceReason(),
                            line.userOverrideLine() ? null : line.catalogItem().netSellingUnitPrice(),
                            line.assumptionsApproved(),
                            line.calculationPlan() == null ? List.of()
                                    : line.calculationPlan().inputs().stream()
                                    .map(input -> new com.buildassist.dto.AiDtos.CalculationParameter(
                                            input.id(), input.value(), input.unit(),
                                            input.source().name(), input.approved(),
                                            "USER_REJECTED_ASSUMPTION".equals(input.evidence())))
                                    .toList(),
                            line.userOverrideLine(),
                            line.catalogItem().itemType());
                })
                .toList();
    }

    private List<String> unmappedRequests(DraftQuoteSnapshot snapshot) {
        return snapshot.workItems().stream()
                .filter(work -> work.recognitionStatus() == WorkRecognitionStatus.UNMAPPED
                        || work.recognitionStatus() == WorkRecognitionStatus.PARTIALLY_MAPPED)
                .map(QuoteWorkSnapshot::originalRequest)
                .toList();
    }

    private List<String> reviewWarnings(EmailDraft draft) {
        return quoteCoverageService.readStringList(draft.getReviewWarningsJson());
    }

    private Set<String> excludedWorkIds(EmailDraft draft) {
        return quoteCoverageService.readDecisions(draft.getCoverageDecisionsJson()).stream()
                .filter(decision -> decision.status() == CoverageStatus.EXCLUDED)
                .map(CoverageDecision::issueId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private CalculationStatus overallStatus(DraftQuoteSnapshot snapshot, EmailDraft draft) {
        Set<String> excluded = new HashSet<>(excludedWorkIds(draft));
        List<QuoteLineSnapshot> lines = snapshot.lineItems().stream()
                .filter(line -> !excluded.contains(line.workId()))
                .toList();
        if (lines.stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.INVALID)) {
            return CalculationStatus.INVALID;
        }
        if (lines.stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.NEEDS_INPUT)) {
            return CalculationStatus.NEEDS_INPUT;
        }
        if (lines.stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.NEEDS_REVIEW)) {
            return CalculationStatus.NEEDS_REVIEW;
        }
        if (snapshot.workItems().stream()
                .filter(work -> !excluded.contains(work.workId()))
                .anyMatch(work -> work.recognitionStatus() != WorkRecognitionStatus.MAPPED)) {
            return CalculationStatus.NEEDS_REVIEW;
        }
        if (lines.stream().noneMatch(QuoteLineSnapshot::chargeable)
                || lines.stream().filter(QuoteLineSnapshot::chargeable)
                .anyMatch(line -> line.netLineTotal() == null)) {
            return CalculationStatus.NEEDS_INPUT;
        }
        return CalculationStatus.VALID;
    }

    private BigDecimal netQuotePrice(DraftQuoteSnapshot snapshot, EmailDraft draft) {
        if (overallStatus(snapshot, draft) != CalculationStatus.VALID) {
            return null;
        }
        Set<String> excluded = new HashSet<>(excludedWorkIds(draft));
        return snapshot.lineItems().stream()
                .filter(line -> !excluded.contains(line.workId()))
                .filter(QuoteLineSnapshot::chargeable)
                .map(QuoteLineSnapshot::netLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void ensurePreviewable(DraftQuoteSnapshot snapshot, EmailDraft draft, Long userId) {
        if (overallStatus(snapshot, draft) != CalculationStatus.VALID) {
            for (QuoteLineSnapshot line : snapshot.lineItems()) {
                if (excludedWorkIds(draft).contains(line.workId())) {
                    continue;
                }
                if (line.calculationResult().status() == CalculationStatus.INVALID) {
                    throw new IllegalArgumentException(
                            "Érvénytelen mennyiségi kalkulációjú sor nem kerülhet PDF-be vagy Gmail-piszkozatba.");
                }
                if (line.calculationResult().status() == CalculationStatus.NEEDS_INPUT) {
                    throw new IllegalArgumentException(
                            "Hiányzó számítási bemenet mellett nem készíthető végleges ajánlat.");
                }
                if (line.calculationResult().status() == CalculationStatus.NEEDS_REVIEW) {
                    throw new IllegalArgumentException(
                            "Ellenőrizetlen AI-feltételezés mellett nem készíthető végleges ajánlat.");
                }
            }
            throw new IllegalArgumentException(
                    "Az ajánlat hiányos vagy ellenőrzést igényel; a PDF és a Gmail-piszkozat nem készíthető el.");
        }
        CoverageResponse coverage = coverageFor(draft, snapshot);
        if (!quoteCoverageService.duplicateChargeWorkIds(
                snapshot, excludedWorkIds(draft)).isEmpty()) {
            throw new IllegalArgumentException(
                    "Bizonyított kettős elszámolás miatt az ajánlat nem véglegesíthető. Távolítsd el az egyik duplikált sort.");
        }
        if (!coverage.analysisFresh()) {
            throw new IllegalArgumentException(
                    "Az ügyfélszöveg vagy a kiegészítő információ megváltozott; futtass új AI-elemzést.");
        }
        boolean unresolvedWork = coverage.workItems().stream()
                .anyMatch(work -> work.status() != CoverageStatus.INCLUDED
                        && work.status() != CoverageStatus.EXCLUDED);
        if (unresolvedWork) {
            throw new IllegalArgumentException(
                    "Minden ügyféligényt le kell fedni vagy kifejezetten ki kell zárni az ajánlatból.");
        }
        TaxSummary tax = taxSummary(snapshot, draft, userId);
        if (!tax.configurationValid()) {
            throw new IllegalArgumentException(tax.taxRegime().equals("REVERSE_CHARGE_UNSUPPORTED")
                    ? "A kiválasztott fordított adózású eset jelenleg nem támogatott."
                    : "ÁFA-körös vállalkozásnál érvényes ÁFA-kulcs szükséges a véglegesítéshez.");
        }
    }

    private String renderDraftHtml(
            Long draftId, DraftQuoteSnapshot snapshot, EmailDraft draft, Long userId) {
        CompanyProfile profile = companyProfileRepository.findByUserId(userId).orElse(null);
        List<DraftLineItem> items = lineItems(snapshot, draft, userId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Az ajánlat nem tartalmaz beárazható sort.");
        }
        BigDecimal grandTotal = items.stream()
                .map(DraftLineItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return generateDraftHtml(draftId, new DraftPricingInfo(items, grandTotal),
                draft.getClientName(),
                draft.isIncludeAdditionalNotes() ? draft.getAdditionalNotes() : null,
                profile);
    }

    private String generateDraftHtml(
            Long draftId, DraftPricingInfo pricing, String clientName,
            String additionalNotes, CompanyProfile profile) {
        java.text.NumberFormat currencyFormat =
                java.text.NumberFormat.getInstance(new Locale("hu", "HU"));
        currencyFormat.setMaximumFractionDigits(0);
        boolean vatRegistered = profile != null && profile.isVatRegistered();
        String taxRegime = profile == null || profile.getTaxRegime() == null
                ? (vatRegistered ? "VAT_REGISTERED" : "AAM") : profile.getTaxRegime();
        BigDecimal vatRate = profile == null ? null : profile.getVatRate();
        if ("REVERSE_CHARGE_UNSUPPORTED".equals(taxRegime)) {
            throw new IllegalArgumentException(
                    "Reverse-charge quotes are not supported.");
        }
        if ("VAT_REGISTERED".equals(taxRegime)
                && (vatRate == null || vatRate.signum() <= 0 || vatRate.compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("A valid VAT rate is required before rendering a taxable quote.");
        }

        List<Map<String, String>> lines = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        for (DraftLineItem item : pricing.items()) {
            BigDecimal netSubtotal = roundHuf(item.subtotal());
            BigDecimal vatAmount = "VAT_REGISTERED".equals(taxRegime)
                    ? roundHuf(netSubtotal.multiply(vatRate).movePointLeft(2))
                    : BigDecimal.ZERO;
            BigDecimal grossSubtotal = netSubtotal.add(vatAmount);
            netTotal = netTotal.add(netSubtotal);
            vatTotal = vatTotal.add(vatAmount);

            Map<String, String> row = new HashMap<>();
            row.put("name", escape(item.name()));
            row.put("quantity", String.format(new Locale("hu", "HU"), "%.2f", item.quantity()));
            row.put("unit", escape(item.unit()));
            BigDecimal customerUnitPrice = "VAT_REGISTERED".equals(taxRegime)
                    ? roundHuf(item.unitPrice().multiply(BigDecimal.ONE.add(vatRate.movePointLeft(2))))
                    : roundHuf(item.unitPrice());
            row.put("unitPrice", currencyFormat.format(customerUnitPrice));
            row.put("netSubtotal", currencyFormat.format(netSubtotal));
            row.put("vatAmount", currencyFormat.format(vatAmount));
            row.put("grossSubtotal", currencyFormat.format(grossSubtotal));
            lines.add(row);
        }

        BigDecimal grossTotalValue = netTotal.add(vatTotal);
        String grossTotal = currencyFormat.format(grossTotalValue);
        Context context = new Context(new Locale("hu", "HU"));
        context.setVariable("lines", lines);
        context.setVariable("vatRegistered", "VAT_REGISTERED".equals(taxRegime));
        context.setVariable("netTotal", currencyFormat.format(netTotal));
        context.setVariable("vatTotal", currencyFormat.format(vatTotal));
        context.setVariable("grossTotal", grossTotal);
        context.setVariable("grandTotal", grossTotal);
        context.setVariable("vatRate", vatRate == null ? "0" : vatRate.stripTrailingZeros().toPlainString());
        context.setVariable("taxRegime", taxRegime);
        context.setVariable("quoteNumber", "AA-" + draftId);
        context.setVariable("quoteDate", java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern(
                        "yyyy. MM. dd.", new Locale("hu", "HU"))));
        context.setVariable("companyName", profile != null ? profile.getCompanyName() : "");
        context.setVariable("legalForm", profile != null ? profile.getLegalForm() : "");
        context.setVariable("country", profile != null ? profile.getCountry() : "");
        context.setVariable("companyAddress", buildCompanyAddress(profile));
        context.setVariable("taxNumber", profile != null ? profile.getTaxNumber() : "");
        context.setVariable("euTaxNumber", profile != null ? profile.getEuTaxNumber() : "");
        context.setVariable("offerTerms", profile != null && profile.getOfferTerms() != null
                ? profile.getOfferTerms() : CompanyProfile.DEFAULT_OFFER_TERMS);
        context.setVariable("clientName",
                clientName != null && !clientName.isBlank() ? clientName : "Érdeklődő");
        context.setVariable("additionalNotes", additionalNotes == null ? "" : additionalNotes);
        context.setVariable("offerorName", profile != null && profile.getOfferorName() != null
                ? profile.getOfferorName() : "");
        context.setVariable("offerorPhone", profile != null && profile.getOfferorPhone() != null
                ? profile.getOfferorPhone() : "");
        return templateEngine.process("draft", context);
    }

    private EmailDraft findAuthorizedDraft(Long draftId, Long userId, boolean forUpdate) {
        EmailDraft draft = (forUpdate
                ? emailDraftRepository.findByIdForUpdate(draftId)
                : emailDraftRepository.findById(draftId))
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));
        Email email = draft.getEmail();
        User user = email == null || email.getGmailConnection() == null
                ? null : email.getGmailConnection().getUser();
        if (user == null || !Objects.equals(user.getId(), userId)) {
            throw new IllegalArgumentException("Draft not found or access denied");
        }
        return draft;
    }

    private void requireExpectedVersion(EmailDraft draft, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion.longValue() != draft.getVersion()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Az ajánlatot időközben más módosította. Töltsd újra, és ellenőrizd a változásokat.");
        }
    }

    private DraftQuoteSnapshot requiredSnapshot(EmailDraft draft) {
        DraftQuoteSnapshot snapshot = snapshotService.load(draft);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "This draft has no structured quote snapshot. Run a new AI analysis before editing or finalizing it.");
        }
        return snapshot;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize structured quote data.", exception);
        }
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isVatRegistered(Long userId) {
        return companyProfileRepository.findByUserId(userId)
                .map(profile -> "VAT_REGISTERED".equals(profile.getTaxRegime())
                        || (profile.getTaxRegime() == null && profile.isVatRegistered()))
                .orElse(false);
    }

    private TaxSummary taxSummary(DraftQuoteSnapshot snapshot, EmailDraft draft, Long userId) {
        CompanyProfile profile = companyProfileRepository.findByUserId(userId).orElse(null);
        String regime = profile == null || profile.getTaxRegime() == null
                ? (profile != null && profile.isVatRegistered() ? "VAT_REGISTERED" : "AAM")
                : profile.getTaxRegime();
        BigDecimal rate = profile == null ? null : profile.getVatRate();
        BigDecimal net = netQuotePrice(snapshot, draft);
        if (net == null) {
            return new TaxSummary(regime, rate, null, null, false);
        }
        if ("AAM".equals(regime)) {
            return new TaxSummary(regime, null, BigDecimal.ZERO.setScale(0), roundHuf(net), true);
        }
        if (!"VAT_REGISTERED".equals(regime) || rate == null
                || rate.signum() <= 0 || rate.compareTo(new BigDecimal("100")) > 0) {
            return new TaxSummary(regime, rate, null, null, false);
        }
        Set<String> excluded = new HashSet<>(excludedWorkIds(draft));
        BigDecimal vat = snapshot.lineItems().stream()
                .filter(line -> !excluded.contains(line.workId()))
                .filter(QuoteLineSnapshot::chargeable)
                .map(QuoteLineSnapshot::netLineTotal)
                .filter(Objects::nonNull)
                .map(total -> roundHuf(total.multiply(rate).movePointLeft(2)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TaxSummary(regime, rate, vat, roundHuf(net).add(vat), true);
    }

    private BigDecimal roundHuf(BigDecimal amount) {
        return amount.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private record TaxSummary(
            String taxRegime, BigDecimal vatRate, BigDecimal vatAmount,
            BigDecimal grossTotal, boolean configurationValid) {
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String buildCompanyAddress(CompanyProfile profile) {
        if (profile == null) {
            return "";
        }
        StringBuilder address = new StringBuilder()
                .append(profile.getPostalCode()).append(' ')
                .append(profile.getCity()).append(", ")
                .append(profile.getStreetName()).append(' ')
                .append(profile.getStreetType()).append(' ')
                .append(profile.getHouseNumber());
        appendAddressPart(address, profile.getBuilding(), "ép.");
        appendAddressPart(address, profile.getStaircase(), "lh.");
        appendAddressPart(address, profile.getFloor(), "em.");
        appendAddressPart(address, profile.getDoor(), "ajtó");
        return address.toString();
    }

    private void appendAddressPart(StringBuilder address, String value, String label) {
        if (value != null && !value.isBlank()) {
            address.append(", ").append(label).append(' ').append(value);
        }
    }

    private void saveDraftToGmail(
            Email email,
            byte[] pdf,
            String filename,
            String clientName,
            CompanyProfile profile,
            Long userId) throws Exception {
        GmailConnection gmailConnection = gmailConnectionRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Gmail connection not found for user"));
        String accessToken = tokenEncryptionService.decrypt(gmailConnection.getAccessTokenEncrypted());
        MimeMessage mimeMessage = quoteEmailMessageFactory.create(
                gmailConnection.getGmailAddress(),
                email.getFromAddress(),
                "Re: " + (email.getSubject() == null ? "(no subject)" : email.getSubject()),
                pdf,
                filename,
                clientName,
                profile);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        String encodedMessage = Base64.getUrlEncoder().encodeToString(buffer.toByteArray());
        String payload = objectMapper.writeValueAsString(Map.of(
                "message", Map.of("raw", encodedMessage)));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/gmail/v1/users/me/drafts"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Gmail draft creation failed with HTTP " + response.statusCode() + ".");
        }
        JsonNode responseJson = objectMapper.readTree(response.body());
        if (responseJson.path("id").asText().isBlank()) {
            throw new IllegalStateException("Gmail did not return a draft identifier.");
        }
    }
}
