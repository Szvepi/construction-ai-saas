package com.buildassist.service;

import com.buildassist.dto.AiDtos.AiExtractedItem;
import com.buildassist.dto.AiDtos.AiExtractionResult;
import com.buildassist.dto.AiDtos.AiRequestedWork;
import com.buildassist.dto.AiDtos.DraftLineItem;
import com.buildassist.dto.AiDtos.DraftPricingInfo;
import com.buildassist.dto.AiDtos.RequestedWork;
import com.buildassist.dto.AiDtos.CoverageDecision;
import com.buildassist.dto.AiDtos.CoverageDecisionRequest;
import com.buildassist.dto.AiDtos.CoverageResponse;
import com.buildassist.dto.AiDtos.CoverageStatus;
import com.buildassist.dto.AiDtos.CoverageWarning;
import com.buildassist.dto.DraftDtos;
import com.buildassist.model.*;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.EmailDraftRepository;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@Slf4j
@Service
public class AiDraftService {

    private final ChatModel chatModel;
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

    public AiDraftService(
            ChatModel chatModel,
            EmailRepository emailRepository,
            CatalogItemRepository catalogItemRepository,
            CompanyProfileRepository companyProfileRepository,
            GmailConnectionRepository gmailConnectionRepository,
            EmailDraftRepository emailDraftRepository,
            TokenEncryptionService tokenEncryptionService,
            SpringTemplateEngine templateEngine,
            QuotePdfService quotePdfService,
            QuoteEmailMessageFactory quoteEmailMessageFactory,
            QuoteCoverageService quoteCoverageService) {
        this.chatModel = chatModel;
        this.emailRepository = emailRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.companyProfileRepository = companyProfileRepository;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.emailDraftRepository = emailDraftRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
        this.templateEngine = templateEngine;
        this.quotePdfService = quotePdfService;
        this.quoteEmailMessageFactory = quoteEmailMessageFactory;
        this.quoteCoverageService = quoteCoverageService;
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse generateAndSaveDraft(Long emailId, Long userId) {
        log.info("Starting draft generation for emailId={}, userId={}", emailId, userId);
        DraftDtos.AnalysisResponse analysis = analyzeEmail(emailId, userId);
        return finalizeDraft(analysis.draftId(),
                new DraftDtos.FinalizeDraftRequest(
                        analysis.clientName(), analysis.lineItems(), analysis.additionalNotes(),
                        analysis.includeAdditionalNotes()),
                userId);
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
        if (catalogItems.isEmpty()) {
            throw new IllegalArgumentException("No catalog items found for user");
        }

        String normalizedNotes = normalizeOptional(additionalNotes);
        AiExtractionResult extractionResult = extractItemsFromEmail(email, catalogItems, normalizedNotes);
        log.debug("AI extraction result: {} items found for client: {}",
                extractionResult.items() != null ? extractionResult.items().size() : 0,
                extractionResult.clientName());

        PricingResult calculation = calculatePricing(extractionResult, catalogItems);
        DraftPricingInfo pricingInfo = calculation.pricing();
        List<String> reviewWarnings = new ArrayList<>(
                extractionResult.reviewWarnings() == null ? List.of() : extractionResult.reviewWarnings());
        reviewWarnings.addAll(calculation.warnings());
        List<RequestedWork> requestedWorks = quoteCoverageService.createRequestedWorks(extractionResult, catalogItems);

        EmailDraft emailDraft = new EmailDraft();
        emailDraft.setEmail(email);
        emailDraft.setDraftBody("");
        emailDraft.setClientName(extractionResult.clientName());
        emailDraft.setAdditionalNotes(normalizedNotes);
        emailDraft.setIncludeAdditionalNotes(true);
        emailDraft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
        emailDraft.setLineItemsJson(serializeJsonList(pricingInfo.items()));
        emailDraft.setReviewWarningsJson(serializeJsonList(reviewWarnings));
        emailDraft.setUnmappedRequestsJson(serializeJsonList(extractionResult.unmappedRequests()));
        emailDraft.setRequestedWorkItemsJson(quoteCoverageService.writeJson(requestedWorks));
        emailDraft.setCoverageDecisionsJson(quoteCoverageService.writeJson(
                quoteCoverageService.captureInitialMatches(requestedWorks, pricingInfo.items())));
        emailDraft.setAcknowledgedWarningsJson("[]");
        emailDraft.setAnalysisFingerprint(quoteCoverageService.analysisFingerprint(email.getBodyText(), normalizedNotes));

        EmailDraft savedDraft = emailDraftRepository.save(emailDraft);

        CoverageResponse coverage = coverageFor(savedDraft);
        return new DraftDtos.AnalysisResponse(
                savedDraft.getId(),
                extractionResult.clientName(),
                pricingInfo.items(),
                extractionResult.unmappedRequests() != null ? extractionResult.unmappedRequests() : List.of(),
                reviewWarnings,
                savedDraft.getAdditionalNotes(),
                savedDraft.isIncludeAdditionalNotes(),
                isVatRegistered(userId),
                coverage.workItems(),
                coverage.warnings(),
                coverage.analysisFresh()
        );
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse saveDraft(Long draftId, DraftDtos.SaveDraftRequest request, Long userId) {
        EmailDraft draft = emailDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        Email email = draft.getEmail();
        if (email == null || email.getGmailConnection() == null || email.getGmailConnection().getUser() == null
                || !Objects.equals(email.getGmailConnection().getUser().getId(), userId)) {
            throw new IllegalArgumentException("Draft not found or access denied");
        }

        if (request == null) {
            throw new IllegalArgumentException("Save request is required");
        }

        List<DraftLineItem> lineItems = request.lineItems() != null
                ? recalculateGrossLineItems(request.lineItems())
                : recalculateGrossLineItems(draft.getLineItems());
        String clientName = request.clientName() != null && !request.clientName().isBlank()
                ? request.clientName()
                : (draft.getClientName() != null ? draft.getClientName() : (email.getFromAddress() != null ? email.getFromAddress() : "Érdeklődő"));

        // Csak menti az adatokat az adatbázisban, nem küld Gmail-hez
        draft.setClientName(clientName);
        if (request.additionalNotes() != null) {
            draft.setAdditionalNotes(normalizeOptional(request.additionalNotes()));
        }
        if (request.includeAdditionalNotes() != null) {
            draft.setIncludeAdditionalNotes(request.includeAdditionalNotes());
        }
        draft.setLineItemsJson(serializeJsonList(lineItems));
        emailDraftRepository.save(draft);

        return toResponse(draft, userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse previewDraft(Long draftId, DraftDtos.FinalizeDraftRequest request, Long userId) {
        EmailDraft draft = emailDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        Email email = draft.getEmail();
        if (email == null || email.getGmailConnection() == null || email.getGmailConnection().getUser() == null
                || !Objects.equals(email.getGmailConnection().getUser().getId(), userId)) {
            throw new IllegalArgumentException("Draft not found or access denied");
        }

        if (request == null) {
            throw new IllegalArgumentException("Preview request is required");
        }

        List<DraftLineItem> lineItems = request.lineItems() != null
                ? recalculateGrossLineItems(request.lineItems())
                : recalculateGrossLineItems(draft.getLineItems());
        String clientName = request.clientName() != null && !request.clientName().isBlank()
                ? request.clientName()
                : (draft.getClientName() != null
                        ? draft.getClientName()
                        : (email.getFromAddress() != null ? email.getFromAddress() : "Érdeklődő"));
        String additionalNotes = request.additionalNotes() != null
                ? normalizeOptional(request.additionalNotes())
                : draft.getAdditionalNotes();
        boolean includeAdditionalNotes = request.includeAdditionalNotes() != null
                ? request.includeAdditionalNotes()
                : draft.isIncludeAdditionalNotes();

        String draftHtmlBody = renderDraftHtml(
                draftId, lineItems, clientName, additionalNotes, includeAdditionalNotes, userId);

        // Csak menti az adatokat, nem küld emailt
        draft.setDraftBody(draftHtmlBody);
        draft.setClientName(clientName);
        draft.setAdditionalNotes(additionalNotes);
        draft.setIncludeAdditionalNotes(includeAdditionalNotes);
        draft.setLineItemsJson(serializeJsonList(lineItems));
        if (draft.getReviewWarningsJson() == null) {
            draft.setReviewWarningsJson(serializeJsonList(List.of()));
        }
        if (draft.getUnmappedRequestsJson() == null) {
            draft.setUnmappedRequestsJson(serializeJsonList(List.of()));
        }
        emailDraftRepository.save(draft);

        return toResponse(draft, userId);
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse finalizeDraft(Long draftId, DraftDtos.FinalizeDraftRequest request, Long userId) {
        EmailDraft draft = emailDraftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        Email email = draft.getEmail();
        if (email == null || email.getGmailConnection() == null || email.getGmailConnection().getUser() == null
                || !Objects.equals(email.getGmailConnection().getUser().getId(), userId)) {
            throw new IllegalArgumentException("Draft not found or access denied");
        }

        if (draft.getDraftBody() == null || draft.getDraftBody().isBlank()) {
            throw new IllegalArgumentException("Draft preview must be generated first");
        }

        if (draft.getStatus() == EmailDraft.DraftStatus.READY) {
            return toResponse(draft, userId);
        }

        DraftDtos.FinalizeDraftRequest finalRequest = request != null
                ? request
                : new DraftDtos.FinalizeDraftRequest(
                        draft.getClientName(), draft.getLineItems(), draft.getAdditionalNotes(),
                        draft.isIncludeAdditionalNotes());
        if (request != null && request.confirmedFingerprint() != null
                && !Objects.equals(request.confirmedFingerprint(), coverageFor(draft).quoteFingerprint())) {
            throw new IllegalArgumentException("Az ajánlat mentés közben megváltozott; ellenőrizd újra.");
        }
        previewDraft(draftId, finalRequest, userId);
        CoverageResponse coverage = coverageFor(draft);
        if (request != null && request.confirmedFingerprint() != null
                && !Objects.equals(request.confirmedFingerprint(), coverage.quoteFingerprint())) {
            throw new IllegalArgumentException("Az ajánlat mentés közben megváltozott; ellenőrizd újra.");
        }
        if (!coverage.unresolvedIssues().isEmpty()
                && (request == null || !Boolean.TRUE.equals(request.confirmIncomplete())
                || !Objects.equals(request.confirmedFingerprint(), coverage.quoteFingerprint()))) {
            throw new IllegalArgumentException("Az ajánlat ellenőrzést igényel. A Gmail-piszkozat megerősítése szükséges.");
        }

        try {
            CompanyProfile profile = companyProfileRepository.findByUserId(userId).orElse(null);
            byte[] pdf = quotePdfService.render(draft.getDraftBody());
            saveDraftToGmail(email, pdf, "arajanlat-" + draftId + ".pdf",
                    draft.getClientName(), profile, userId);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create Gmail draft with PDF quote", ex);
        }

        draft.setStatus(EmailDraft.DraftStatus.READY);
        emailDraftRepository.save(draft);

        return toResponse(draft, userId);
    }

    @Transactional
    public byte[] generatePdf(Long draftId, Long userId) {
        EmailDraft draft = emailDraftRepository.findById(draftId)
            .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        Email email = draft.getEmail();
        if (email == null || email.getGmailConnection() == null || email.getGmailConnection().getUser() == null
                || !Objects.equals(email.getGmailConnection().getUser().getId(), userId)) {
            throw new IllegalArgumentException("Draft not found or access denied");
        }
        if (draft.getDraftBody() == null || draft.getDraftBody().isBlank()) {
            throw new IllegalArgumentException("Generate the quote preview before requesting its PDF");
        }

        String currentHtml = renderDraftHtml(
                draftId, draft.getLineItems(), draft.getClientName(), draft.getAdditionalNotes(),
                draft.isIncludeAdditionalNotes(), userId);
        draft.setDraftBody(currentHtml);
        emailDraftRepository.save(draft);
        return quotePdfService.render(currentHtml);
    }

    private String renderDraftHtml(
            Long draftId, List<DraftLineItem> lineItems, String clientName, String additionalNotes,
            boolean includeAdditionalNotes, Long userId) {
        CompanyProfile profile = companyProfileRepository.findByUserId(userId).orElse(null);
        List<DraftLineItem> recalculatedLineItems = recalculateGrossLineItems(lineItems);
        BigDecimal total = recalculatedLineItems.stream()
                .map(DraftLineItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return generateDraftHtml(
                draftId, new DraftPricingInfo(recalculatedLineItems, total), clientName,
                includeAdditionalNotes ? additionalNotes : null, profile);
    }

    private List<DraftLineItem> recalculateGrossLineItems(List<DraftLineItem> lineItems) {
        List<DraftLineItem> normalized = lineItems.stream()
                .map(item -> {
                    if (item == null || item.quantity() == null || item.unitPrice() == null) {
                        throw new IllegalArgumentException("Quote line items must include quantity and gross unit price");
                    }
                    BigDecimal grossSubtotal = item.quantity().multiply(item.unitPrice())
                            .setScale(0, RoundingMode.HALF_UP);
                    return new DraftLineItem(
                            item.name(), item.unit(), item.quantity(), item.unitPrice(), grossSubtotal,
                            item.lineItemId() == null || item.lineItemId().isBlank()
                                    ? UUID.randomUUID().toString() : item.lineItemId(),
                            item.catalogItemId());
                })
                .toList();
        Set<String> lineItemIds = new HashSet<>();
        for (DraftLineItem item : normalized) {
            if (item.lineItemId().length() > 100 || !lineItemIds.add(item.lineItemId())) {
                throw new IllegalArgumentException("Quote line item identifiers must be unique.");
            }
        }
        return normalized;
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse updateCoverageDecision(
            Long draftId, String issueId, CoverageDecisionRequest request, Long userId) {
        if (request == null || request.status() == null || request.draft() == null) {
            throw new IllegalArgumentException("A döntés és az aktuális ajánlati adatok megadása kötelező.");
        }
        saveDraft(draftId, request.draft(), userId);
        EmailDraft draft = emailDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));
        List<RequestedWork> works = requestedWorksFor(draft);
        Optional<RequestedWork> work = works.stream()
                .filter(item -> item.workId().equals(issueId))
                .findFirst();
        if (work.isPresent()) {
            List<CoverageDecision> decisions = quoteCoverageService.readDecisions(draft.getCoverageDecisionsJson());
            List<CoverageDecision> updated = quoteCoverageService.setDecision(
                    decisions, issueId, request.status(), request.lineItemId(), draft.getLineItems());
            draft.setCoverageDecisionsJson(quoteCoverageService.writeJson(updated));
        } else {
            List<CoverageWarning> warnings = quoteCoverageService.warningItems(
                    coverageWarnings(draft), new HashSet<>());
            boolean warningExists = warnings.stream().anyMatch(warning -> warning.warningId().equals(issueId));
            if (!warningExists) {
                throw new IllegalArgumentException("Az ellenőrzési elem nem található ennél az ajánlatnál.");
            }
            List<String> acknowledged = new ArrayList<>(
                    quoteCoverageService.readStringList(draft.getAcknowledgedWarningsJson()));
            acknowledged.remove(issueId);
            if (request.status() == CoverageStatus.INCLUDED) {
                acknowledged.add(issueId);
            } else if (request.status() != CoverageStatus.NEEDS_REVIEW) {
                throw new IllegalArgumentException("Érvénytelen AI-figyelmeztetés állapot.");
            }
            draft.setAcknowledgedWarningsJson(quoteCoverageService.writeJson(acknowledged));
        }
        emailDraftRepository.save(draft);
        return toResponse(draft, userId);
    }

    @Transactional(readOnly = true)
    public CoverageResponse getCoverage(Long draftId, Long userId) {
        EmailDraft draft = emailDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));
        Email email = draft.getEmail();
        if (email == null || email.getGmailConnection() == null || email.getGmailConnection().getUser() == null
                || !Objects.equals(email.getGmailConnection().getUser().getId(), userId)) {
            throw new IllegalArgumentException("Draft not found or access denied");
        }
        return coverageFor(draft);
    }

    private DraftDtos.GenerateDraftResponse toResponse(EmailDraft draft, Long userId) {
        CoverageResponse coverage = coverageFor(draft);
        return new DraftDtos.GenerateDraftResponse(
                draft.getId(), draft.getDraftBody(), draft.getUnmappedRequests(), draft.getReviewWarnings(),
                draft.getLineItems(), draft.getClientName(), draft.getAdditionalNotes(),
                draft.isIncludeAdditionalNotes(), isVatRegistered(userId),
                coverage.workItems(), coverage.warnings(), coverage.analysisFresh(),
                draft.getStatus() == EmailDraft.DraftStatus.READY);
    }

    private CoverageResponse coverageFor(EmailDraft draft) {
        List<RequestedWork> works = requestedWorksFor(draft);
        List<CoverageDecision> decisions = quoteCoverageService.readDecisions(draft.getCoverageDecisionsJson());
        List<String> acknowledged = quoteCoverageService.readStringList(draft.getAcknowledgedWarningsJson());
        Email email = draft.getEmail();
        boolean analysisFresh = draft.getAnalysisFingerprint() == null || Objects.equals(
                draft.getAnalysisFingerprint(),
                quoteCoverageService.analysisFingerprint(
                        email == null ? null : email.getBodyText(), draft.getAdditionalNotes()));
        return quoteCoverageService.response(
                works, decisions, draft.getLineItems(), coverageWarnings(draft),
                acknowledged, analysisFresh, quoteCoverageService.quoteFingerprint(draft));
    }

    private List<String> coverageWarnings(EmailDraft draft) {
        List<String> warnings = new ArrayList<>(draft.getReviewWarnings());
        List<DraftLineItem> lineItems = draft.getLineItems();
        for (int index = 0; index < lineItems.size(); index++) {
            DraftLineItem item = lineItems.get(index);
            if (item.unitPrice() != null && item.unitPrice().signum() == 0) {
                warnings.add("Az ajánlat " + (index + 1) + ". sora („" + item.name()
                        + "”) 0 Ft-os egységárú. Ellenőrizd, hogy ez tudatos-e.");
            }
        }
        return List.copyOf(warnings);
    }

    private List<RequestedWork> requestedWorksFor(EmailDraft draft) {
        if (draft.getRequestedWorkItemsJson() == null || draft.getRequestedWorkItemsJson().isBlank()) {
            return quoteCoverageService.legacyRequestedWorks(draft.getUnmappedRequests(), draft.getId());
        }
        return quoteCoverageService.readRequestedWorks(draft.getRequestedWorkItemsJson());
    }

    private String serializeJsonList(List<?> items) {
        if (items == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception ex) {
            log.warn("Failed to serialize draft list data: {}", ex.getMessage());
            return "[]";
        }
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean isVatRegistered(Long userId) {
        return companyProfileRepository.findByUserId(userId)
                .map(CompanyProfile::isVatRegistered)
                .orElse(false);
    }

    private AiExtractionResult extractItemsFromEmail(
            Email email, List<CatalogItem> catalogItems, String additionalNotes) {
        String catalogReference = buildCatalogReference(catalogItems);
        log.info("extractItemsFromEmail: catalogReference: {}", catalogReference);
        String emailBody = email.getBodyText() != null ? email.getBodyText() : "";

        BeanOutputConverter<AiExtractionResult> outputConverter = new BeanOutputConverter<>(AiExtractionResult.class);

        String prompt = """
                        You are a precise data extraction assistant for a Hungarian construction CRM.
                        Analyze the provided HUNGARIAN email body and map requested construction services/materials to the contractor's CATALOG REFERENCE.
                
                        CRITICAL RULES:
                        1. EXTRACT CLIENT NAME: Look for the client's full name or first name from the email signature or introduction (e.g. 'Üdvözlettel, Kovács János' -> 'Kovács János'). If not found, return null.
                        2. Match the client's request to the closest catalog item by Hungarian semantic meaning (e.g., 'szegélyezés' -> Szegélykő rakása, 'térkövezés' -> Térkő lerakás).
                        3. Extract every single requested work item (labor, ground work, base layer, borders, material).
                        4. If the email mentions a room or yard size (e.g., "40 m2-es udvar") but the catalog item requires volume or surface area, extract the number (40) as the quantity.
                        5. UNMAPPED ITEMS: If the client explicitly requests a work item or material that is NOT in the catalog reference (e.g. 'konténer rendelés', 'sitt elszállítás'), list it in the 'unmappedRequests' array.
                        6. For perimeter/border items, if only total area (m2) is mentioned in the email, extract the total area number as quantity, so our perimeter strategy can calculate it correctly.
                        7. MANDATORY WORKFLOW DEPENDENCIES (IMPLIED PREPARATION WORK):
                           Construction services consist of consecutive technological steps. If a primary work type is requested (e.g. 'térkövezés', 'burkolás') and the email DOES NOT explicitly state that preparation work is already done (e.g., "a tükör már ki van ásva", "az alap már kész"):
                           - You MUST automatically include necessary preparatory and foundational catalog items from the `{catalogReference}` (specifically: ground excavation/preparation ('Tereprendezés és földkiemelés') and sub-base bedding ('Alapozó zúzottkő réteg terítése')).
                           - Assign the extracted surface area quantity (m2) to these preparatory catalog items as well.
                
                        8. SANITY CHECKS & CONTRACTOR REVIEW WARNINGS (`review_warnings`):
                           Analyze the geometry and technical logic of the request. Generate internal review warnings for the contractor in a dedicated list if you detect potential issues:
                           - INCOMPLETE PERIMETER / BORDER: If edging/curbing ('szegélykő') is requested for fewer than 4 sides (e.g., "csak 2 oldalra kérek szegélyt") or partial perimeter, AND the email does NOT mention connecting to an existing structure (e.g., house wall, fence, existing paving):
                             Add a warning: "Az ügyfél csak [X] oldalra kért szegélykövet. Ha a terület nem csatlakozik meglévő építményhez/burkolathoz, a teljes körbekerítéshez több szegélykőre lesz szükség."
                           - AMBIGUOUS SCOPE: Flag any missing parameters that could change the quote significantly (e.g., unknown ground type, missing depth for excavation).
                        9. Consider the contractor's supplementary consultation notes when extracting work items and quantities, while keeping the email as the source for the client's identity.
                        10. Also return `requested_works`: one entry for each distinct work the customer explicitly requested or that appears in the supplementary consultation notes. Do NOT include preparation work you inferred yourself unless the customer requested it. Each entry must have `name`, `source` (`EMAIL` or `ADDITIONAL_INFO`), `catalog_item_id` (the matching catalog ID only when the match is clear, otherwise null), `quantity` (only when explicitly stated or reliably calculable, otherwise null; use the original requested/base quantity before catalog calculation strategies such as waste allowances), `unit` (only when known, otherwise null), and a short `reason`. Keep the existing `items`, `unmapped_requests`, and `review_warnings` fields as well.
                        11. If the same work appears in both sources, return it once and use `EMAIL` as its source. Do not infer that an item is covered merely because a broad or generic catalog item exists.
                
                        CATALOG REFERENCE (ID - Name - Unit):
                        {catalogReference}
                
                        {format}
                
                        EMAIL BODY:
                        {emailBody}

                        CONTRACTOR'S SUPPLEMENTARY NOTES:
                        {additionalNotes}
                """;

        try {
            String aiResponse = ChatClient.create(chatModel)
                    .prompt()
                    .user(userSpec -> userSpec
                            .text(prompt)
                            .param("catalogReference", catalogReference)
                            .param("format", outputConverter.getFormat())
                            .param("emailBody", emailBody)
                            .param("additionalNotes", additionalNotes != null ? additionalNotes : "Nincs kiegészítő megjegyzés.")
                    )
                    .call()
                    .content();

            log.info("AI raw response:\n{}", aiResponse);
            AiExtractionResult result = outputConverter.convert(aiResponse);

            return result != null ? result : new AiExtractionResult(null, List.of(), List.of(), List.of(), List.of());
        } catch (Exception ex) {
            log.error("AI extraction failed for email id: {}", email.getId(), ex);
            throw new RuntimeException("Failed to extract items using AI: " + ex.getMessage(), ex);
        }
    }

    private String buildCatalogReference(List<CatalogItem> items) {
        StringBuilder sb = new StringBuilder();
        for (CatalogItem item : items) {
            sb.append(String.format(
                    "ID: %d | Name: %s | Unit: %s | Price: %.0f Ft%n",
                    item.getId(),
                    item.getName(),
                    item.getUnit(),
                    item.getUnitPrice()
            ));
        }
        return sb.toString();
    }

    PricingResult calculatePricing(AiExtractionResult extraction, List<CatalogItem> catalogItems) {
        Map<Long, CatalogItem> catalogMap = new HashMap<>();
        for (CatalogItem item : catalogItems) {
            catalogMap.put(item.getId(), item);
        }

        List<DraftLineItem> lineItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        if (extraction.items() != null) {
            for (AiExtractedItem extractedItem : extraction.items()) {
                CatalogItem catalogItem = catalogMap.get(extractedItem.catalogItemId());

                if (catalogItem != null && extractedItem.quantity() != null) {
                    BigDecimal baseQuantity = extractedItem.quantity();
                    String inputUnit = requestedUnitFor(extraction, extractedItem);
                    CalculationStrategy strategy = catalogItem.getCalculationStrategy();
                    if (strategy != null && !strategy.supportsUnits(inputUnit, catalogItem.getUnit())) {
                        warnings.add("A(z) „" + catalogItem.getName()
                                + "” mennyiségének mértékegysége nem ismert vagy nem kompatibilis; "
                                + "automatikus átszámítás nem történt.");
                    }
                    BigDecimal adjustedQuantity = adjustQuantity(
                            baseQuantity, strategy, inputUnit, catalogItem.getUnit());
                    BigDecimal unitPrice = catalogItem.getUnitPrice();
                    BigDecimal subtotal = unitPrice.multiply(adjustedQuantity).setScale(0, RoundingMode.HALF_UP);

                    lineItems.add(new DraftLineItem(
                            catalogItem.getName(),
                            catalogItem.getUnit(),
                            adjustedQuantity,
                            unitPrice,
                            subtotal,
                            UUID.randomUUID().toString(),
                            catalogItem.getId()
                    ));

                    grandTotal = grandTotal.add(subtotal);
                }
            }
        }

        return new PricingResult(new DraftPricingInfo(lineItems, grandTotal), List.copyOf(warnings));
    }

    private String requestedUnitFor(AiExtractionResult extraction, AiExtractedItem extractedItem) {
        if (extraction.requestedWorks() == null) {
            return null;
        }
        List<String> matchingUnits = extraction.requestedWorks().stream()
                .filter(work -> work != null
                        && Objects.equals(work.catalogItemId(), extractedItem.catalogItemId())
                        && work.quantity() != null
                        && work.quantity().compareTo(extractedItem.quantity()) == 0
                        && work.unit() != null && !work.unit().isBlank())
                .map(AiRequestedWork::unit)
                .distinct()
                .toList();
        return matchingUnits.size() == 1 ? matchingUnits.get(0) : null;
    }

    private BigDecimal adjustQuantity(
            BigDecimal baseQuantity, CalculationStrategy strategy, String inputUnit, String outputUnit) {
        return strategy == null ? baseQuantity : strategy.adjustQuantity(baseQuantity, inputUnit, outputUnit);
    }

    record PricingResult(DraftPricingInfo pricing, List<String> warnings) {
    }

    private String generateDraftHtml(
            Long draftId, DraftPricingInfo pricing, String clientName, String additionalNotes, CompanyProfile profile) {
        java.text.NumberFormat currencyFormat = java.text.NumberFormat.getInstance(new java.util.Locale("hu", "HU"));
        currencyFormat.setMaximumFractionDigits(0);
        boolean vatRegistered = profile != null && profile.isVatRegistered();

        List<Map<String, String>> lines = new ArrayList<>();
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        for (DraftLineItem item : pricing.items()) {
            BigDecimal grossSubtotal = item.subtotal();
            BigDecimal netSubtotal = vatRegistered
                    ? grossSubtotal.divide(new BigDecimal("1.27"), 0, RoundingMode.HALF_UP)
                    : grossSubtotal;
            BigDecimal vatAmount = grossSubtotal.subtract(netSubtotal);
            netTotal = netTotal.add(netSubtotal);
            vatTotal = vatTotal.add(vatAmount);

            Map<String, String> m = new HashMap<>();
            m.put("name", escape(item.name()));
            m.put("quantity", String.format(new java.util.Locale("hu", "HU"), "%.2f", item.quantity()));
            m.put("unit", escape(item.unit()));
            m.put("unitPrice", currencyFormat.format(item.unitPrice()));
            m.put("netSubtotal", currencyFormat.format(netSubtotal));
            m.put("vatAmount", currencyFormat.format(vatAmount));
            m.put("grossSubtotal", currencyFormat.format(grossSubtotal));
            lines.add(m);
        }

        String grossTotal = currencyFormat.format(pricing.grandTotal());

        Context ctx = new Context(new java.util.Locale("hu", "HU"));
        ctx.setVariable("lines", lines);
        ctx.setVariable("vatRegistered", vatRegistered);
        ctx.setVariable("netTotal", currencyFormat.format(netTotal));
        ctx.setVariable("vatTotal", currencyFormat.format(vatTotal));
        ctx.setVariable("grossTotal", grossTotal);
        ctx.setVariable("grandTotal", grossTotal);
        ctx.setVariable("vatRate", "27");
        ctx.setVariable("quoteNumber", "AA-" + draftId);
        ctx.setVariable("quoteDate", java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy. MM. dd.", new java.util.Locale("hu", "HU"))));
        ctx.setVariable("companyName", profile != null ? profile.getCompanyName() : "");
        ctx.setVariable("legalForm", profile != null ? profile.getLegalForm() : "");
        ctx.setVariable("country", profile != null ? profile.getCountry() : "");
        ctx.setVariable("companyAddress", buildCompanyAddress(profile));
        ctx.setVariable("taxNumber", profile != null ? profile.getTaxNumber() : "");
        ctx.setVariable("euTaxNumber", profile != null ? profile.getEuTaxNumber() : "");
        ctx.setVariable("offerTerms", profile != null && profile.getOfferTerms() != null ? profile.getOfferTerms() : CompanyProfile.DEFAULT_OFFER_TERMS);

        // Ha nem sikerült nevet kinyerni, egy általános megszólítást adunk
        String greetingName = (clientName != null && !clientName.isBlank()) ? clientName : "Érdeklődő";
        ctx.setVariable("clientName", greetingName);
        ctx.setVariable("additionalNotes", additionalNotes != null ? additionalNotes : "");
        ctx.setVariable("offerorName", profile != null && profile.getOfferorName() != null
                ? profile.getOfferorName() : "");
        ctx.setVariable("offerorPhone", profile != null && profile.getOfferorPhone() != null
                ? profile.getOfferorPhone() : "");

        return templateEngine.process("draft", ctx);
    }

    private String escape(String text) {
        if (text == null) return "";
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
            Email email, byte[] pdf, String filename, String clientName, CompanyProfile profile, Long userId) throws Exception {
        GmailConnection gmailConnection = gmailConnectionRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Gmail connection not found for user"));

        String accessTokenEncrypted = gmailConnection.getAccessTokenEncrypted();
        String accessToken = tokenEncryptionService.decrypt(accessTokenEncrypted);

        MimeMessage mimeMessage = quoteEmailMessageFactory.create(
                gmailConnection.getGmailAddress(),
                email.getFromAddress(),
                "Re: " + (email.getSubject() != null ? email.getSubject() : "(no subject)"),
                pdf,
                filename,
                clientName,
                profile);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        String encodedMessage = Base64.getUrlEncoder().encodeToString(buffer.toByteArray());

        String draftPayload = objectMapper.writeValueAsString(Map.of(
                "message", Map.of("raw", encodedMessage)
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/gmail/v1/users/me/drafts"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(draftPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            log.error("Gmail API error {}: {}", response.statusCode(), response.body());
            throw new RuntimeException("Failed to create Gmail draft. Status: " + response.statusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        log.info("Draft created in Gmail with ID: {}", responseJson.path("id").asText());
    }
}