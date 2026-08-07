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

/**
 * AI-powered draft generation service.
 * <p>
 * Pipeline:
 * 1. Fetch email and user's catalog items
 * 2. Use Spring AI ChatModel to extract structured data from email (which items requested)
 * 3. Calculate pricing based on extracted items and catalog
 * 4. Generate professional HTML draft email
 * 5. Save draft to Gmail using OAuth2 tokens
 */
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

    /**
     * Main entry point: generate and save a draft for the given email.
     *
     * @param emailId the email ID to generate a draft for
     * @param userId  the user ID who owns both the email and catalog
     * @throws IllegalArgumentException if email or user not found
     * @throws RuntimeException         if AI extraction or Gmail API fails
     */
    @Transactional
    public DraftDtos.GenerateDraftResponse generateAndSaveDraft(Long emailId, Long userId) {
        log.info("Starting draft generation for emailId={}, userId={}", emailId, userId);

        // Step 1: Fetch email and validate ownership
        Email email = emailRepository.findByIdAndGmailConnectionUserId(emailId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Email not found or access denied"));

        // Step 2: Fetch all catalog items for the user
        List<CatalogItem> catalogItems = catalogItemRepository.findByUserId(userId);
        if (catalogItems.isEmpty()) {
            throw new IllegalArgumentException("No catalog items found for user");
        }

        // Step 3: Use AI to extract structured data from email body
        AiExtractionResult extractionResult = extractItemsFromEmail(email, catalogItems);
        log.debug("AI extraction result: {} items found", extractionResult.items().size());

        // Step 4: Calculate pricing
        DraftPricingInfo pricingInfo = calculatePricing(extractionResult, catalogItems);

        // Step 5: Generate HTML draft body
        String draftHtmlBody = generateDraftHtml(pricingInfo);

        // Step 6: Save draft to database
        EmailDraft emailDraft = new EmailDraft();
        emailDraft.setEmail(email);
        emailDraft.setDraftBody(draftHtmlBody);
        emailDraft.setStatus(EmailDraft.DraftStatus.DRAFT);
        EmailDraft savedDraft = emailDraftRepository.save(emailDraft);
        log.info("Draft saved to database with id={}", savedDraft.getId());

        // Step 7: Save draft to Gmail
        try {
            // TODO ez nem biztos hogy kell ilyen formában. Lehet hogy csak email küldés lesz egyből.
//            saveDraftToGmail(email, draftHtmlBody, userId);
            log.info("Draft successfully saved to Gmail");
        } catch (Exception ex) {
            log.error("Failed to save draft to Gmail, but draft exists in DB", ex);
            throw new RuntimeException("Failed to save draft to Gmail: " + ex.getMessage(), ex);
        }

        return new DraftDtos.GenerateDraftResponse(savedDraft.getId(), draftHtmlBody);
    }

    /**
     * Uses Spring AI ChatModel to extract structured data from email body.
     * Sends the email body and catalog reference to LLM, expecting JSON response.
     */
    private AiExtractionResult extractItemsFromEmail(Email email, List<CatalogItem> catalogItems) {
        String catalogReference = buildCatalogReference(catalogItems);
        String emailBody = email.getBodyText() != null ? email.getBodyText() : "";

        // Spring AI strukturált kimenet-konverter
        BeanOutputConverter<AiExtractionResult> outputConverter = new BeanOutputConverter<>(AiExtractionResult.class);

        String prompt = """
                        You are a precise data extraction assistant for a Hungarian construction CRM.
                        Analyze the provided HUNGARIAN email body and map ALL requested construction services and materials to the contractor's CATALOG REFERENCE.
                
                        CRITICAL RULES:
                        1. Do NOT wrap the response in ```json ... ``` markdown blocks. Return ONLY the raw JSON string.
                        2. Match the client's request to the closest catalog item by Hungarian semantic meaning (e.g., 'szegélyezés' -> Szegélykő rakása, 'térkövezés' -> Térkő lerakás).
                        3. Extract every single requested work item (labor, ground work, base layer, borders/szegélyezés, material).
                        4. If the email mentions a room or yard size (e.g., "40 m2-es udvar") but the catalog item requires volume or surface area, extract the number (40) as the quantity, and our system will handle the calculation.
                        5. If an item cannot be mapped to the catalog, or has no quantifiable amount, omit it from the items list.
                
                        CATALOG REFERENCE (ID - Name - Unit):
                        {catalogReference}
                
                        {format}
                
                        EMAIL BODY:
                        {emailBody}
                """;

        try {
            // A Spring AI automatikusan elvégzi a JSON parsingot a DTO-ba
            AiExtractionResult result = ChatClient.create(chatModel)
                    .prompt()
                    .user(userSpec -> userSpec
                            .text(prompt)
                            .param("catalogReference", catalogReference)
                            .param("format", outputConverter.getFormat())
                            .param("emailBody", emailBody)
                    )
                    .call()
                    .entity(AiExtractionResult.class);

            log.debug("AI extraction successful, extracted items count: {}",
                    result != null && result.items() != null ? result.items().size() : 0);

            return result != null ? result : new AiExtractionResult(List.of());
        } catch (Exception ex) {
            log.error("AI extraction failed for email id: {}", email.getId(), ex);
            throw new RuntimeException("Failed to extract items using AI: " + ex.getMessage(), ex);
        }
    }

    /**
     * Összeállítja a katalógus elemek referencialistáját az LLM számára.
     * Kifejezetten Ft (HUF) formátumot használ.
     */
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

    /**
     * Calculates pricing for extracted items, applying industry-specific calculation strategies.
     */
    private DraftPricingInfo calculatePricing(AiExtractionResult extraction, List<CatalogItem> catalogItems) {
        Map<Long, CatalogItem> catalogMap = new HashMap<>();
        for (CatalogItem item : catalogItems) {
            catalogMap.put(item.getId(), item);
        }

        List<DraftLineItem> lineItems = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (AiExtractedItem extractedItem : extraction.items()) {
            CatalogItem catalogItem = catalogMap.get(extractedItem.catalogItemId());

            if (catalogItem != null && extractedItem.quantity() != null) {
                BigDecimal baseQuantity = extractedItem.quantity();

                // 1. Mennyiség korrigálása a beállított stratégia alapján
                BigDecimal adjustedQuantity = adjustQuantity(baseQuantity, catalogItem.getCalculationStrategy());

                // 2. Részösszeg számítása a korrigált mennyiséggel
                BigDecimal unitPrice = catalogItem.getUnitPrice();
                BigDecimal subtotal = unitPrice.multiply(adjustedQuantity).setScale(0, RoundingMode.HALF_UP); // Forintban nincs fillér

                // A DraftLineItem-nek már a korrigált (valós) mennyiséget adjuk át,
                // így a kiküldött ajánlatban is a jó m2/db/m3 fog szerepelni.
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

        return new DraftPricingInfo(lineItems, grandTotal);
    }

    /**
     * Helper method to adjust the extracted quantity based on the calculation strategy.
     */
    private BigDecimal adjustQuantity(BigDecimal baseQuantity, CalculationStrategy strategy) {
        if (strategy == null) {
            return baseQuantity;
        }

        switch (strategy) {
            case WALL_SURFACE_3X:
                // Festő ökölszabály: Alapterület x 3 = Becsült falfelület
                return baseQuantity.multiply(new BigDecimal("3"));

            case WASTE_PERCENTAGE_10:
                // Burkoló ökölszabály: Alapterület + 10% vágási veszteség
                return baseQuantity.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP);

            case ROOM_PERIMETER:
                // Szegélyléc/lábazat számítás: négyzetes szobát feltételezve kerület = sqrt(alapterület) * 4
                double area = baseQuantity.doubleValue();
                if (area <= 0) return baseQuantity;
                double perimeter = Math.sqrt(area) * 4;
                return BigDecimal.valueOf(perimeter).setScale(2, RoundingMode.HALF_UP);

            case VOLUME_BY_THICKNESS:
                // Kőműves alapozás/betonozás: Terület x Alapértelmezett vastagság (itt pl. 15 cm = 0.15 m)
                // Tipp: Ezt a fix 0.15-öt később lecserélheted a felhasználó profiljában mentett egyedi értékre is.
                return baseQuantity.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP);

            case DIRECT:
            default:
                // Nincs módosítás
                return baseQuantity;
        }
    }

    /**
     * Generates a professional HTML email with pricing breakdown.
     */
    private String generateDraftHtml(DraftPricingInfo pricing) {
        // Formázó a magyar forintértékekhez (pl. 1 011 750 Ft)
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

        return templateEngine.process("draft", ctx);
    }

    /**
     * Simple HTML escape for text content.
     */
    private String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Saves the draft to Gmail using OAuth2 access token via Gmail REST API.
     */
    private void saveDraftToGmail(Email email, String draftHtmlBody, Long userId) throws Exception {
        // Load Gmail connection and decrypt access token
        GmailConnection gmailConnection = gmailConnectionRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Gmail connection not found for user"));

        String accessTokenEncrypted = gmailConnection.getAccessTokenEncrypted();
        String accessToken = tokenEncryptionService.decrypt(accessTokenEncrypted);

        // Create MIME message
        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);
        MimeMessage mimeMessage = new MimeMessage(session);

        // Set email headers
        mimeMessage.setFrom(new InternetAddress(gmailConnection.getGmailAddress()));
        mimeMessage.addRecipient(Message.RecipientType.TO, new InternetAddress(email.getFromAddress()));
        mimeMessage.setSubject("Re: " + (email.getSubject() != null ? email.getSubject() : "(no subject)"));
        mimeMessage.setContent(draftHtmlBody, "text/html; charset=UTF-8");

        // Encode message to base64
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        byte[] messageBytes = buffer.toByteArray();
        String encodedMessage = Base64.getUrlEncoder().encodeToString(messageBytes);

        // Create draft payload JSON
        String draftPayload = objectMapper.writeValueAsString(Map.of(
                "message", Map.of("raw", encodedMessage)
        ));

        try {
            // Create HTTP request to Gmail API
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/gmail/v1/users/me/drafts"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(draftPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                log.error("Gmail API returned 401 Unauthorized - token may be expired");
                throw new RuntimeException("Gmail authentication failed. Token may be expired.");
            }

            if (response.statusCode() >= 400) {
                log.error("Gmail API error {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Failed to create Gmail draft. Status: " + response.statusCode());
            }

            // Parse response to get draft ID
            JsonNode responseJson = objectMapper.readTree(response.body());
            String draftId = responseJson.path("id").asText();
            log.info("Draft created in Gmail with ID: {}", draftId);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gmail API request interrupted", ex);
        }
    }
}
