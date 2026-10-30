package com.buildassist.service;

import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.DraftQuoteSnapshot.QuoteLineSnapshot;
import com.buildassist.calculation.DraftQuoteSnapshot.QuoteWorkSnapshot;
import com.buildassist.calculation.NetMoney;
import com.buildassist.calculation.Quantity;
import com.buildassist.calculation.QuantityUnit;
import com.buildassist.calculation.WorkRecognitionStatus;
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
                analysis.includeAdditionalNotes(), false, coverage.quoteFingerprint()), userId);
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
                netQuotePrice(snapshot, savedDraft));
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse saveDraft(
            Long draftId, DraftDtos.SaveDraftRequest request, Long userId) {
        if (request == null) {
            throw new IllegalArgumentException("Save request is required.");
        }
        EmailDraft draft = findAuthorizedDraft(draftId, userId, false);
        applyDraftChanges(draft, request, userId);
        emailDraftRepository.save(draft);
        return toResponse(draft, userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse previewDraft(
            Long draftId, DraftDtos.FinalizeDraftRequest request, Long userId) {
        if (request == null) {
            throw new IllegalArgumentException("Preview request is required.");
        }
        EmailDraft draft = findAuthorizedDraft(draftId, userId, false);
        applyDraftChanges(draft, new DraftDtos.SaveDraftRequest(
                request.clientName(), request.lineItems(), request.additionalNotes(),
                request.includeAdditionalNotes()), userId);
        DraftQuoteSnapshot snapshot = requiredSnapshot(draft);
        ensurePreviewable(snapshot, draft, userId);
        String body = renderDraftHtml(draft.getId(), snapshot, draft, userId);
        draft.setDraftBody(body);
        emailDraftRepository.save(draft);
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
            applyDraftChanges(draft, new DraftDtos.SaveDraftRequest(
                    request.clientName(), request.lineItems(), request.additionalNotes(),
                    request.includeAdditionalNotes()), userId);
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
        EmailDraft draft = findAuthorizedDraft(draftId, userId, false);
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
            draft.setCoverageDecisionsJson("[]");
            draft.setAcknowledgedWarningsJson("[]");
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
        Map<String, BigDecimal> submittedQuantities = new HashMap<>();

        for (DraftLineItem item : submitted) {
            if (item == null || item.lineItemId() == null || !seen.add(item.lineItemId())) {
                throw new IllegalArgumentException("Az ajánlati sorazonosítóknak jelen kell lenniük és egyedinek kell lenniük.");
            }
            DraftLineItem serverLine = expectedById.get(item.lineItemId());
            if (serverLine == null
                    || !Objects.equals(item.name(), serverLine.name())
                    || !Objects.equals(item.unit(), serverLine.unit())
                    || !Objects.equals(item.catalogItemId(), serverLine.catalogItemId())
                    || !"NET".equals(item.priceBasis())
                    || item.unitPrice() == null
                    || item.unitPrice().compareTo(serverLine.unitPrice()) != 0) {
                throw new IllegalArgumentException(
                        "A katalógus megnevezése, egysége és ára kizárólag a szerver által meghatározott adat.");
            }
            if (item.quantity() == null || item.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Az ajánlati mennyiségnek pozitívnak kell lennie.");
            }
            submittedQuantities.put(item.lineItemId(), item.quantity());
        }
        if (!seen.equals(expectedById.keySet())) {
            throw new IllegalArgumentException("Az ajánlati sorok nem hagyhatók el a lefedettségi döntés helyett.");
        }

        List<QuoteLineSnapshot> updatedLines = new ArrayList<>();
        boolean changed = false;
        for (QuoteLineSnapshot line : snapshot.lineItems()) {
            BigDecimal submittedQuantity = submittedQuantities.get(line.lineItemId());
            if (submittedQuantity == null) {
                updatedLines.add(line);
                continue;
            }
            Quantity override;
            try {
                override = new Quantity(submittedQuantity,
                        QuantityUnit.fromCode(line.catalogItem().unit()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("A kézi mennyiség egysége vagy értéke érvénytelen.", exception);
            }
            Quantity currentQuantity = line.userOverrideQuantity() == null
                    ? line.customerChargeQuantity() : line.userOverrideQuantity();
            if (currentQuantity.convertTo(override.unit()).value().compareTo(override.value()) == 0) {
                updatedLines.add(line);
                continue;
            }
            Quantity storedOverride = override.convertTo(line.customerChargeQuantity().unit())
                    .value().compareTo(line.customerChargeQuantity().value()) == 0 ? null : override;
            updatedLines.add(copyWithOverride(line, storedOverride));
            changed = true;
        }
        return changed
                ? new DraftQuoteSnapshot(snapshot.schemaVersion(), null, snapshot.workItems(), updatedLines)
                : snapshot;
    }

    private QuoteLineSnapshot copyWithOverride(QuoteLineSnapshot line, Quantity override) {
        return new QuoteLineSnapshot(
                line.lineItemId(), line.workId(), line.catalogItemId(), line.catalogItem(),
                line.settlementMode(), line.requirements(), line.calculationPlan(),
                line.calculationResult(), line.requiredQuantity(), line.procurementQuantity(),
                line.customerChargeQuantity(), override, line.assumptionsApproved(),
                line.approvedFingerprint(), line.explanation(), null, line.chargeable(),
                line.procurementPackageCount(), line.assumptions(),
                line.procurementOutputReference(), line.procurementPackageCountReference());
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
                netQuotePrice(snapshot, draft));
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
                .filter(line -> line.calculationResult().status() == CalculationStatus.VALID)
                .filter(line -> line.netLineTotal() != null && line.catalogItem() != null)
                .map(line -> {
                    Quantity quantity = line.userOverrideQuantity() == null
                            ? line.customerChargeQuantity() : line.userOverrideQuantity();
                    BigDecimal netUnitPrice = NetMoney.of(line.catalogItem().netSellingUnitPrice()).huf();
                    BigDecimal subtotal = line.netLineTotal();
                    return new DraftLineItem(
                            line.catalogItem().name(),
                            line.catalogItem().unit(),
                            quantity.value(),
                            netUnitPrice,
                            subtotal,
                            line.lineItemId(),
                            line.catalogItemId());
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
        if (isVatRegistered(userId)) {
            throw new IllegalArgumentException(
                    "A katalógus nettó áraihoz nincs beállított ajánlati ÁFA-kulcs; "
                            + "az ÁFA-kulcsot nem találgatjuk, ezért PDF/Gmail véglegesítés nem elérhető.");
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
        if (vatRegistered) {
            throw new IllegalArgumentException(
                    "An explicit VAT rate is required before rendering a VAT-registered quote.");
        }

        List<Map<String, String>> lines = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        for (DraftLineItem item : pricing.items()) {
            BigDecimal grossSubtotal = item.subtotal();
            BigDecimal netSubtotal = grossSubtotal;
            BigDecimal vatAmount = BigDecimal.ZERO;
            netTotal = netTotal.add(netSubtotal);
            vatTotal = vatTotal.add(vatAmount);

            Map<String, String> row = new HashMap<>();
            row.put("name", escape(item.name()));
            row.put("quantity", String.format(new Locale("hu", "HU"), "%.2f", item.quantity()));
            row.put("unit", escape(item.unit()));
            row.put("unitPrice", currencyFormat.format(item.unitPrice()));
            row.put("netSubtotal", currencyFormat.format(netSubtotal));
            row.put("vatAmount", currencyFormat.format(vatAmount));
            row.put("grossSubtotal", currencyFormat.format(grossSubtotal));
            lines.add(row);
        }

        String grossTotal = currencyFormat.format(pricing.grandTotal());
        Context context = new Context(new Locale("hu", "HU"));
        context.setVariable("lines", lines);
        context.setVariable("vatRegistered", vatRegistered);
        context.setVariable("netTotal", currencyFormat.format(netTotal));
        context.setVariable("vatTotal", currencyFormat.format(vatTotal));
        context.setVariable("grossTotal", grossTotal);
        context.setVariable("grandTotal", grossTotal);
        context.setVariable("vatRate", "0");
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
                .map(CompanyProfile::isVatRegistered)
                .orElse(false);
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
