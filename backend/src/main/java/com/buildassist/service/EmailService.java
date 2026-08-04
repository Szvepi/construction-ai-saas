package com.buildassist.service;

import com.buildassist.dto.EmailDtos;
import com.buildassist.dto.EmailDtos.EmailDetailResponse;
import com.buildassist.dto.EmailDtos.EmailSummaryResponse;
import com.buildassist.dto.EmailDtos.RefreshEmailsResponse;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.model.GmailConnection;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class EmailService {

    private final EmailRepository emailRepository;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final GmailService gmailService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailService(
            EmailRepository emailRepository,
            GmailConnectionRepository gmailConnectionRepository,
            GmailService gmailService) {
        this.emailRepository = emailRepository;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.gmailService = gmailService;
    }

    public List<EmailSummaryResponse> listEmails(Long userId) {
        Optional<GmailConnection> gmailConn = gmailConnectionRepository.findByUserId(userId);
        if (gmailConn.isEmpty()) {
            return List.of();
        }

        GmailConnection conn = gmailConn.get();
        List<Email> emails = emailRepository.findByGmailConnectionIdOrderByEmailReceivedAtDesc(conn.getId());

        return emails.stream()
                .map(email -> new EmailSummaryResponse(
                        email.getId(),
                        email.getSubject(),
                        email.getFromAddress(),
                        email.getEmailReceivedAt(),
                        email.isReplied(),
                        email.getCategory()))
                .toList();
    }

    public EmailDetailResponse getEmail(Long userId, Long emailId) {
        Optional<GmailConnection> gmailConn = gmailConnectionRepository.findByUserId(userId);
        if (gmailConn.isEmpty()) {
            throw new IllegalStateException("Gmail not connected for user " + userId);
        }

        Optional<Email> emailOpt = emailRepository.findById(emailId);
        if (emailOpt.isEmpty()) {
            throw new IllegalArgumentException("Email not found: " + emailId);
        }

        Email email = emailOpt.get();
        // Verify that the email belongs to the user's Gmail connection
        if (!email.getGmailConnection().getId().equals(gmailConn.get().getId())) {
            throw new IllegalStateException("Email does not belong to user's Gmail connection");
        }

        return new EmailDetailResponse(
                email.getId(),
                email.getSubject(),
                email.getFromAddress(),
                email.getBodyText(),
                email.getEmailReceivedAt(),
                email.isReplied(),
                email.getCategory());
    }

    @Transactional
    public EmailDtos.UpdateCategoryResponse updateCategory(Long userId, Long emailId, EmailCategory newCategory) {
        Optional<GmailConnection> gmailConn = gmailConnectionRepository.findByUserId(userId);
        if (gmailConn.isEmpty()) {
            throw new IllegalStateException("Gmail not connected for user " + userId);
        }

        Optional<Email> emailOpt = emailRepository.findById(emailId);
        if (emailOpt.isEmpty()) {
            throw new IllegalArgumentException("Email not found: " + emailId);
        }

        Email email = emailOpt.get();
        if (!email.getGmailConnection().getId().equals(gmailConn.get().getId())) {
            throw new IllegalStateException("Email does not belong to user's Gmail connection");
        }

        // OTHER kategóriába nem lehet áthelyezni
        if (newCategory == EmailCategory.OTHER) {
            throw new IllegalArgumentException("Cannot move email to OTHER category");
        }

        email.setCategory(newCategory);
        emailRepository.save(email);

        return new EmailDtos.UpdateCategoryResponse(email.getId(), email.getCategory());
    }

    @Transactional
    public RefreshEmailsResponse refreshFromGmail(Long userId) {
        log.info("Refreshing emails from Gmail for user {}", userId);
        Optional<GmailConnection> gmailConnOpt = gmailConnectionRepository.findByUserId(userId);
        if (gmailConnOpt.isEmpty()) {
            throw new IllegalStateException("Gmail not connected for user " + userId);
        }

        GmailConnection gmailConn = gmailConnOpt.get();
        String gmailAddress = gmailConn.getGmailAddress();

        // Get OAuth2 access token
        Optional<String> tokenOpt = gmailService.getOAuth2AccessToken(gmailAddress);
        if (tokenOpt.isEmpty()) {
            throw new IllegalStateException("Cannot obtain Gmail access token for user " + userId);
        }

        log.info("Gmail access token obtained. UserId: {}", userId);
        String accessToken = tokenOpt.get();
        int fetchedCount = fetchAndSaveEmails(gmailConn, accessToken, 50);

        return new RefreshEmailsResponse(fetchedCount);
    }

    private int fetchAndSaveEmails(GmailConnection gmailConn, String accessToken, int maxResults) {
        try {
            // Step 1: Get message IDs from Gmail API - only from primary category
            String query = "label:INBOX category:primary -category:promotions -category:social -category:updates -in:spam -in:trash";
            // A query URI-kompatibilis kódolása
            String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");

            String listUrl = "https://www.googleapis.com/gmail/v1/users/me/messages"
                    + "?maxResults=" + maxResults
                    + "&q=" + encodedQuery;

            HttpRequest listReq = HttpRequest.newBuilder()
                    .uri(URI.create(listUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> listResp = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() >= 400) {
                throw new RuntimeException("Gmail API returned " + listResp.statusCode() + ": " + listResp.body());
            }

            JsonNode listJson = objectMapper.readTree(listResp.body());
            JsonNode messages = listJson.get("messages");
            if (messages == null || !messages.isArray()) {
                return 0;
            }

            int savedCount = 0;
            log.info("Found {} messages to process.", messages.size());
            for (JsonNode msgNode : messages) {
                String messageId = msgNode.get("id").asText();
                String threadId = msgNode.get("threadId").asText();

                // Check if already saved
                log.info("Checking if message {} already exists in the database.", messageId);
                if (emailRepository.existsByGmailConnectionIdAndGmailMessageId(gmailConn.getId(), messageId)) {
                    continue;
                }

                // Fetch full message details
                log.info("Fetching details for message {}.", messageId);
                Email email = fetchMessageDetails(gmailConn, accessToken, messageId);
                if (email != null) {
                    emailRepository.save(email);
                    savedCount++;
                }
            }

            log.info("Saved {} emails in the database.", savedCount);
            return savedCount;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to refresh emails from Gmail", ex);
        }
    }

    private Email fetchMessageDetails(GmailConnection gmailConn, String accessToken, String messageId) {
        try {
            // 1. LÉPÉS: Könnyűsúlyú lekérés (Csak a fejléceket kérjük le, a törzset és csatolmányokat NEM)
            String metadataUrl = "https://www.googleapis.com/gmail/v1/users/me/messages/" + messageId + "?format=metadata";
            HttpRequest metaReq = HttpRequest.newBuilder()
                    .uri(URI.create(metadataUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> metaResp = httpClient.send(metaReq, HttpResponse.BodyHandlers.ofString());
            if (metaResp.statusCode() >= 400) {
                return null;
            }

            JsonNode metaJson = objectMapper.readTree(metaResp.body());
            JsonNode headers = metaJson.path("payload").path("headers");

            // 2. LÉPÉS: Elő-szűrés a fejlécek, a feladó és a tárgy alapján
            EmailCategory category = categorizeMessage(headers, messageId);
            if (category == EmailCategory.SPAM) {
                // Spam - le sem töltjük a teljes törzset!
                return createEmailFromGmailResponse(gmailConn, messageId, headers, metaJson, category);
            }

            // 3. LÉPÉS: Ha nem spam, csak AKKOR töltjük le a teljes levelet (format=full)
            String fullMessageUrl = "https://www.googleapis.com/gmail/v1/users/me/messages/" + messageId + "?format=full";
            HttpRequest fullReq = HttpRequest.newBuilder()
                    .uri(URI.create(fullMessageUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> fullResp = httpClient.send(fullReq, HttpResponse.BodyHandlers.ofString());
            if (fullResp.statusCode() >= 400) {
                return null;
            }

            JsonNode fullJson = objectMapper.readTree(fullResp.body());
            JsonNode fullHeaders = fullJson.path("payload").path("headers");

            return createEmailFromGmailResponse(gmailConn, messageId, fullHeaders, fullJson, category);
        } catch (Exception ex) {
            log.error("An error occurred while processing email with ID {}: {}", messageId, ex.getMessage());
            return null;
        }
    }

    private Email createEmailFromGmailResponse(GmailConnection gmailConn, String messageId, JsonNode fullHeaders, JsonNode fullJson, EmailCategory category) {
        String subject = extractHeader(fullHeaders, "Subject");
        String fromAddress = extractHeader(fullHeaders, "From");
        String bodyText = extractBody(fullJson.path("payload"));

        Email email = new Email();
        email.setGmailConnection(gmailConn);
        email.setGmailMessageId(messageId);
        email.setSubject(subject);
        email.setFromAddress(fromAddress);
        email.setBodyText(bodyText);
        email.setSyncedAt(OffsetDateTime.now());
        email.setEmailReceivedAt(extractEmailReceivedDate(fullJson));
        email.setReplied(false);
        email.setCategory(category);
        return email;
    }

    /**
     * Zéró költségű kategorizálás a Gmail fejlécek ellenőrzésére.
     * SPAM: Automata üzenetek, tömeges emailek
     * QUOTE_REQUEST: Árajánlat kérés
     * OTHER: Egyéb
     */
    private EmailCategory categorizeMessage(JsonNode headers, String messageId) {
        if (headers == null || !headers.isArray()) {
            return EmailCategory.OTHER;
        }

        String from = extractHeader(headers, "From");
        String subject = extractHeader(headers, "Subject");

        // A) Hírlevél és tömeges üzenet fejlécek ellenőrzése
        for (JsonNode header : headers) {
            String name = header.path("name").asText("");
            String value = header.path("value").asText("");

            if ("List-Unsubscribe".equalsIgnoreCase(name) || "List-ID".equalsIgnoreCase(name)) {
                log.info("Categorizing message {} as SPAM (mailing list header {}).", messageId, name);
                return EmailCategory.SPAM;
            }
            if ("Precedence".equalsIgnoreCase(name) && value.toLowerCase().contains("bulk")) {
                log.info("Categorizing message {} as SPAM (Precedence header indicates bulk).", messageId);
                return EmailCategory.SPAM;
            }
        }

        // B) Automata / Rendszer feladók kiszűrése (no-reply, newsletter stb.)
        if (from != null) {
            String lowerFrom = from.toLowerCase();
            if (lowerFrom.contains("no-reply@") || lowerFrom.contains("noreply@") ||
                    lowerFrom.contains("newsletter@") || lowerFrom.contains("support@") ||
                    lowerFrom.contains("mailer-daemon@") || lowerFrom.contains("donotreply@")) {
                log.info("Categorizing message {} as SPAM (automated sender: {}).", messageId, from);
                return EmailCategory.SPAM;
            }
        }

        // C) Tárgy alapú feketelista (rendszerértesítők, jelszóemlékeztetők, számlák)
        if (subject != null) {
            String lowerSubject = subject.toLowerCase();
            if (lowerSubject.contains("biztonsági értesítés") || lowerSubject.contains("jelszó") ||
                    lowerSubject.contains("password reset") || lowerSubject.contains("sikeres fizetés") ||
                    lowerSubject.contains("bejelentkezés") || lowerSubject.contains("login alert")) {
                log.info("Categorizing message {} as SPAM (subject blacklist).", messageId);
                return EmailCategory.SPAM;
            }
        }

        // TODO ezt a részt még át kell gondolni
        // D) Árajánlat kérés detektálása
        if (subject != null) {
            String lowerSubject = subject.toLowerCase();
            if (lowerSubject.contains("árajánlat") || lowerSubject.contains("quote") ||
                    lowerSubject.contains("offer") || lowerSubject.contains("ár") ||
                    lowerSubject.contains("price") || lowerSubject.contains("költség")) {
                log.info("Categorizing message {} as QUOTE_REQUEST (subject match).", messageId);
                return EmailCategory.QUOTE_REQUEST;
            }
        }

        log.info("Categorizing message {} as OTHER.", messageId);
        return EmailCategory.OTHER;
    }

    private OffsetDateTime extractEmailReceivedDate(JsonNode fullJson) {
        // Olvassuk ki a Gmail belső milliós időbélyegét
        if (fullJson.hasNonNull("internalDate")) {
            long internalDateMillis = fullJson.get("internalDate").asLong();

            // Átalakítás Java Instant-tá (UTC alapú pontos időpillanat)
            return Instant.ofEpochMilli(internalDateMillis)
                    .atZone(ZoneId.of("Europe/Budapest"))
                    .toOffsetDateTime();
        } else {
            // Biztonsági tartalék, ha valamiért hiányozna (elvileg sosem hiányzik)
            return OffsetDateTime.now();
        }
    }

    private String extractHeader(JsonNode headers, String headerName) {
        if (headers == null || !headers.isArray()) {
            return null;
        }

        for (JsonNode header : headers) {
            if (headerName.equals(header.get("name").asText())) {
                return header.get("value").asText();
            }
        }
        return null;
    }

    private String extractBody(JsonNode payload) {
        if (payload == null) {
            return "";
        }

        // Try to get the body from the part
        JsonNode body = payload.get("body");
        if (body != null && body.has("data")) {
            String data = body.get("data").asText();
            try {
                return new String(java.util.Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return data;
            }
        }

        // If multipart, try to get text/plain part
        JsonNode parts = payload.get("parts");
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                String mimeType = part.get("mimeType").asText("");
                if ("text/plain".equals(mimeType)) {
                    JsonNode partBody = part.get("body");
                    if (partBody != null && partBody.has("data")) {
                        String data = partBody.get("data").asText();
                        try {
                            return new String(java.util.Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
                        } catch (IllegalArgumentException e) {
                            return data;
                        }
                    }
                }
            }
        }

        return "";
    }
}

