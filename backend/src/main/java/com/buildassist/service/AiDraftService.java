package com.buildassist.service;

import com.buildassist.dto.AiDtos.AiExtractedItem;
import com.buildassist.dto.AiDtos.AiExtractionResult;
import com.buildassist.dto.AiDtos.DraftLineItem;
import com.buildassist.dto.AiDtos.DraftPricingInfo;
import com.buildassist.dto.DraftDtos;
import com.buildassist.model.*;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.EmailDraftRepository;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
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
    private final GmailConnectionRepository gmailConnectionRepository;
    private final EmailDraftRepository emailDraftRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SpringTemplateEngine templateEngine;

    public AiDraftService(
            ChatModel chatModel,
            EmailRepository emailRepository,
            CatalogItemRepository catalogItemRepository,
            GmailConnectionRepository gmailConnectionRepository,
            EmailDraftRepository emailDraftRepository,
            TokenEncryptionService tokenEncryptionService,
            SpringTemplateEngine templateEngine) {
        this.chatModel = chatModel;
        this.emailRepository = emailRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.emailDraftRepository = emailDraftRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
        this.templateEngine = templateEngine;
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse generateAndSaveDraft(Long emailId, Long userId) {
        log.info("Starting draft generation for emailId={}, userId={}", emailId, userId);
        DraftDtos.AnalysisResponse analysis = analyzeEmail(emailId, userId);
        return finalizeDraft(analysis.draftId(),
                new DraftDtos.FinalizeDraftRequest(analysis.clientName(), analysis.lineItems()),
                userId);
    }

    @Transactional
    public DraftDtos.AnalysisResponse analyzeEmail(Long emailId, Long userId) {
        Email email = emailRepository.findByIdAndGmailConnectionUserId(emailId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Email not found or access denied"));

        List<CatalogItem> catalogItems = catalogItemRepository.findAllByUserId(userId);
        if (catalogItems.isEmpty()) {
            throw new IllegalArgumentException("No catalog items found for user");
        }

        AiExtractionResult extractionResult = extractItemsFromEmail(email, catalogItems);
        log.debug("AI extraction result: {} items found for client: {}",
                extractionResult.items() != null ? extractionResult.items().size() : 0,
                extractionResult.clientName());

        DraftPricingInfo pricingInfo = calculatePricing(extractionResult, catalogItems);

        EmailDraft emailDraft = new EmailDraft();
        emailDraft.setEmail(email);
        emailDraft.setDraftBody("");
        emailDraft.setClientName(extractionResult.clientName());
        emailDraft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
        emailDraft.setLineItemsJson(serializeJsonList(pricingInfo.items()));
        emailDraft.setReviewWarningsJson(serializeJsonList(extractionResult.reviewWarnings()));
        emailDraft.setUnmappedRequestsJson(serializeJsonList(extractionResult.unmappedRequests()));

        EmailDraft savedDraft = emailDraftRepository.save(emailDraft);

        return new DraftDtos.AnalysisResponse(
                savedDraft.getId(),
                extractionResult.clientName(),
                pricingInfo.items(),
                extractionResult.unmappedRequests() != null ? extractionResult.unmappedRequests() : List.of(),
                extractionResult.reviewWarnings() != null ? extractionResult.reviewWarnings() : List.of()
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

        List<DraftLineItem> lineItems = request.lineItems() != null && !request.lineItems().isEmpty()
                ? request.lineItems()
                : draft.getLineItems();
        String clientName = request.clientName() != null && !request.clientName().isBlank()
                ? request.clientName()
                : (draft.getClientName() != null ? draft.getClientName() : (email.getFromAddress() != null ? email.getFromAddress() : "Érdeklődő"));

        // Csak menti az adatokat az adatbázisban, nem küld Gmail-hez
        draft.setClientName(clientName);
        draft.setLineItemsJson(serializeJsonList(lineItems));
        emailDraftRepository.save(draft);

        return new DraftDtos.GenerateDraftResponse(draftId, draft.getDraftBody(), draft.getUnmappedRequests(), draft.getReviewWarnings(), draft.getLineItems(), draft.getClientName());
    }

    @Transactional
    public DraftDtos.GenerateDraftResponse finalizeDraft(Long draftId, DraftDtos.FinalizeDraftRequest request, Long userId) {
        EmailDraft draft = emailDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        Email email = draft.getEmail();
        if (email == null || email.getGmailConnection() == null || email.getGmailConnection().getUser() == null
                || !Objects.equals(email.getGmailConnection().getUser().getId(), userId)) {
            throw new IllegalArgumentException("Draft not found or access denied");
        }

        if (request == null) {
            throw new IllegalArgumentException("Finalize request is required");
        }

        List<DraftLineItem> lineItems = request.lineItems() != null && !request.lineItems().isEmpty()
                ? request.lineItems()
                : draft.getLineItems();
        String clientName = request.clientName() != null && !request.clientName().isBlank()
                ? request.clientName()
                : (email.getFromAddress() != null ? email.getFromAddress() : "Érdeklődő");

        BigDecimal total = lineItems.stream()
                .map(item -> item.subtotal() == null ? BigDecimal.ZERO : item.subtotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String draftHtmlBody = generateDraftHtml(new DraftPricingInfo(lineItems, total), clientName);
        try {
            saveDraftToGmail(email, draftHtmlBody, userId);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save finalized draft to Gmail", ex);
        }

        draft.setDraftBody(draftHtmlBody);
        draft.setClientName(clientName);
        draft.setStatus(EmailDraft.DraftStatus.READY);
        draft.setLineItemsJson(serializeJsonList(lineItems));
        if (draft.getReviewWarningsJson() == null) {
            draft.setReviewWarningsJson(serializeJsonList(List.of()));
        }
        if (draft.getUnmappedRequestsJson() == null) {
            draft.setUnmappedRequestsJson(serializeJsonList(List.of()));
        }
        emailDraftRepository.save(draft);

        return new DraftDtos.GenerateDraftResponse(draftId, draftHtmlBody, draft.getUnmappedRequests(), draft.getReviewWarnings(), draft.getLineItems(), draft.getClientName());
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

    private AiExtractionResult extractItemsFromEmail(Email email, List<CatalogItem> catalogItems) {
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
                
                        CATALOG REFERENCE (ID - Name - Unit):
                        {catalogReference}
                
                        {format}
                
                        EMAIL BODY:
                        {emailBody}
                """;

        try {
            String aiResponse = ChatClient.create(chatModel)
                    .prompt()
                    .user(userSpec -> userSpec
                            .text(prompt)
                            .param("catalogReference", catalogReference)
                            .param("format", outputConverter.getFormat())
                            .param("emailBody", emailBody)
                    )
                    .call()
                    .content();

            log.info("AI raw response:\n{}", aiResponse);
            AiExtractionResult result = outputConverter.convert(aiResponse);

            return result != null ? result : new AiExtractionResult(null, List.of(), List.of(), List.of());
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

    private DraftPricingInfo calculatePricing(AiExtractionResult extraction, List<CatalogItem> catalogItems) {
        Map<Long, CatalogItem> catalogMap = new HashMap<>();
        for (CatalogItem item : catalogItems) {
            catalogMap.put(item.getId(), item);
        }

        List<DraftLineItem> lineItems = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        if (extraction.items() != null) {
            for (AiExtractedItem extractedItem : extraction.items()) {
                CatalogItem catalogItem = catalogMap.get(extractedItem.catalogItemId());

                if (catalogItem != null && extractedItem.quantity() != null) {
                    BigDecimal baseQuantity = extractedItem.quantity();
                    BigDecimal adjustedQuantity = adjustQuantity(baseQuantity, catalogItem.getCalculationStrategy());
                    BigDecimal unitPrice = catalogItem.getUnitPrice();
                    BigDecimal subtotal = unitPrice.multiply(adjustedQuantity).setScale(0, RoundingMode.HALF_UP);

                    lineItems.add(new DraftLineItem(
                            catalogItem.getName(),
                            catalogItem.getUnit(),
                            adjustedQuantity,
                            unitPrice,
                            subtotal
                    ));

                    grandTotal = grandTotal.add(subtotal);
                }
            }
        }

        return new DraftPricingInfo(lineItems, grandTotal);
    }

    private BigDecimal adjustQuantity(BigDecimal baseQuantity, CalculationStrategy strategy) {
        if (strategy == null) return baseQuantity;

        switch (strategy) {
            case WALL_SURFACE_3X:
                return baseQuantity.multiply(new BigDecimal("3"));
            case WASTE_PERCENTAGE_10:
                return baseQuantity.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP);
            case ROOM_PERIMETER:
                double area = baseQuantity.doubleValue();
                if (area <= 0) return baseQuantity;
                return BigDecimal.valueOf(Math.sqrt(area) * 4).setScale(2, RoundingMode.HALF_UP);
            case VOLUME_BY_THICKNESS:
                return baseQuantity.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP);
            case DIRECT:
            default:
                return baseQuantity;
        }
    }

    private String generateDraftHtml(DraftPricingInfo pricing, String clientName) {
        java.text.NumberFormat currencyFormat = java.text.NumberFormat.getInstance(new java.util.Locale("hu", "HU"));
        currencyFormat.setMaximumFractionDigits(0);

        List<Map<String, String>> lines = new ArrayList<>();
        for (DraftLineItem item : pricing.items()) {
            Map<String, String> m = new HashMap<>();
            m.put("name", escape(item.name()));
            m.put("quantity", String.format(new java.util.Locale("hu", "HU"), "%.2f", item.quantity()));
            m.put("unit", escape(item.unit()));
            m.put("unitPrice", currencyFormat.format(item.unitPrice()));
            m.put("subtotal", currencyFormat.format(item.subtotal()));
            lines.add(m);
        }

        String grandTotal = currencyFormat.format(pricing.grandTotal());

        Context ctx = new Context(new java.util.Locale("hu", "HU"));
        ctx.setVariable("lines", lines);
        ctx.setVariable("grandTotal", grandTotal);

        // Ha nem sikerült nevet kinyerni, egy általános megszólítást adunk
        String greetingName = (clientName != null && !clientName.isBlank()) ? clientName : "Érdeklődő";
        ctx.setVariable("clientName", greetingName);

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

    private void saveDraftToGmail(Email email, String draftHtmlBody, Long userId) throws Exception {
        GmailConnection gmailConnection = gmailConnectionRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Gmail connection not found for user"));

        String accessTokenEncrypted = gmailConnection.getAccessTokenEncrypted();
        String accessToken = tokenEncryptionService.decrypt(accessTokenEncrypted);

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);
        MimeMessage mimeMessage = new MimeMessage(session);

        mimeMessage.setFrom(new InternetAddress(gmailConnection.getGmailAddress()));
        mimeMessage.addRecipient(Message.RecipientType.TO, new InternetAddress(email.getFromAddress()));
        mimeMessage.setSubject("Re: " + (email.getSubject() != null ? email.getSubject() : "(no subject)"));
        mimeMessage.setContent(draftHtmlBody, "text/html; charset=UTF-8");

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