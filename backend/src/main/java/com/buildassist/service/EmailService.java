package com.buildassist.service;

import com.buildassist.dto.DraftDtos;
import com.buildassist.dto.EmailDtos;
import com.buildassist.dto.EmailDtos.EmailDetailResponse;
import com.buildassist.dto.EmailDtos.EmailSummaryResponse;
import com.buildassist.dto.EmailDtos.EmailSearchResponse;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.model.EmailDraft;
import com.buildassist.model.GmailConnection;
import com.buildassist.repository.EmailDraftRepository;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.buildassist.repository.EmailRepository.CategoryCount;
import com.buildassist.repository.EmailRepository.SearchRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class EmailService {

    private final EmailDraftRepository emailDraftRepository;
    private final EmailRepository emailRepository;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final AiDraftService aiDraftService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailService(
            EmailRepository emailRepository,
            EmailDraftRepository emailDraftRepository,
            GmailConnectionRepository gmailConnectionRepository,
            AiDraftService aiDraftService) {
        this.emailRepository = emailRepository;
        this.emailDraftRepository = emailDraftRepository;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.aiDraftService = aiDraftService;
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
                        email.getCategory(),
                        emailDraftRepository.findTopByEmailIdOrderByCreatedAtDesc(email.getId())
                                .map(com.buildassist.model.EmailDraft::getStatus)
                                .orElse(null),
                        email.getClassificationStatus(),
                        email.getClassificationReason(),
                        email.getClassificationEvidence()))
                .toList();
    }

    @Transactional(readOnly = true)
    public EmailSearchResponse searchEmails(
            Long userId,
            String query,
            EmailCategory category,
            String draftStatus,
            int pageNumber,
            int pageSize,
            String direction) {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        if (direction == null || !(direction.equalsIgnoreCase("asc") || direction.equalsIgnoreCase("desc"))) {
            throw new IllegalArgumentException("direction must be 'asc' or 'desc'");
        }
        validateDraftStatus(draftStatus);

        String searchPattern = toSearchPattern(query);
        Map<EmailCategory, Long> categoryCounts = emptyCategoryCounts();
        Optional<GmailConnection> gmailConnection = gmailConnectionRepository.findByUserId(userId);
        if (gmailConnection.isEmpty()) {
            return new EmailSearchResponse(
                    List.of(), pageNumber, pageSize, 0, 0, categoryCounts, 0);
        }

        Long gmailConnectionId = gmailConnection.get().getId();
        String categoryName = category == null ? null : category.name();
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        Page<SearchRow> results = direction.equalsIgnoreCase("asc")
                ? emailRepository.searchEmailsAscending(
                        gmailConnectionId, searchPattern, categoryName, draftStatus, pageable)
                : emailRepository.searchEmailsDescending(
                        gmailConnectionId, searchPattern, categoryName, draftStatus, pageable);

        List<CategoryCount> counts = emailRepository.countSearchEmailsByCategory(
                gmailConnectionId, searchPattern);
        for (CategoryCount count : counts) {
            categoryCounts.put(EmailCategory.valueOf(count.getCategory()), count.getCount());
        }
        long reviewCount = emailRepository.countReviewEmails(gmailConnectionId, searchPattern);

        List<EmailSummaryResponse> content = results.getContent().stream()
                .map(this::toSummaryResponse)
                .toList();

        return new EmailSearchResponse(
                content,
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages(),
                categoryCounts,
                reviewCount);
    }

    private void validateDraftStatus(String draftStatus) {
        if (draftStatus == null || draftStatus.equals("REVIEW")) {
            return;
        }
        try {
            EmailDraft.DraftStatus.valueOf(draftStatus);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported draftStatus: " + draftStatus);
        }
    }

    private EmailSummaryResponse toSummaryResponse(SearchRow row) {
        return new EmailSummaryResponse(
                row.getId(),
                row.getSubject(),
                row.getFromAddress(),
                row.getReceivedAt().atOffset(ZoneOffset.UTC),
                Boolean.TRUE.equals(row.getReplied()),
                EmailCategory.valueOf(row.getCategory()),
                row.getDraftStatus() == null ? null : EmailDraft.DraftStatus.valueOf(row.getDraftStatus()),
                row.getClassificationStatus(),
                row.getClassificationReason(),
                row.getClassificationEvidence());
    }

    private String toSearchPattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        if (query.length() > 200) {
            throw new IllegalArgumentException("q must be at most 200 characters");
        }
        String escapedQuery = query.trim()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escapedQuery + "%";
    }

    private Map<EmailCategory, Long> emptyCategoryCounts() {
        Map<EmailCategory, Long> counts = new EnumMap<>(EmailCategory.class);
        for (EmailCategory emailCategory : EmailCategory.values()) {
            counts.put(emailCategory, 0L);
        }
        return counts;
    }

    @Transactional(readOnly = true)
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

        DraftDtos.GenerateDraftResponse draftEmail =
                emailDraftRepository.findTopByEmailIdOrderByCreatedAtDesc(emailId)
                        .filter(draft -> draft.getQuoteSnapshotJson() != null
                                && !draft.getQuoteSnapshotJson().isBlank())
                        .map(draft -> aiDraftService.responseForEmail(draft, userId))
                        .orElse(null);

        return new EmailDetailResponse(
                email.getId(),
                email.getSubject(),
                email.getFromAddress(),
                email.getBodyText(),
                email.getEmailReceivedAt(),
                email.isReplied(),
                email.getCategory(),
                email.getClassificationStatus(),
                email.getClassificationReason(),
                email.getClassificationEvidence(),
                draftEmail);
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

        if (newCategory == null || newCategory == EmailCategory.CLASSIFICATION_REVIEW) {
            throw new IllegalArgumentException("Choose a final email category");
        }

        email.setCategory(newCategory);
        email.setClassificationSource("MANUAL");
        email.setClassificationStatus("CLASSIFIED");
        email.setClassificationVersion(null);
        email.setClassificationReason("Kézi besorolás");
        email.setClassificationEvidence(null);
        email.setClassificationRetryAt(null);
        emailRepository.save(email);

        return new EmailDtos.UpdateCategoryResponse(email.getId(), email.getCategory());
    }

    Email createEmailFromGmailResponse(
            GmailConnection gmailConn,
            String messageId,
            JsonNode fullHeaders,
            JsonNode fullJson,
            EmailCategory category) {
        String subject = extractHeader(fullHeaders, "Subject");
        String fromAddress = extractHeader(fullHeaders, "From");
        String bodyText = category == EmailCategory.SPAM ? "" : extractBody(fullJson.path("payload"));

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
        email.setClassificationMetadata(extractClassificationMetadata(fullHeaders));
        return email;
    }

    private String extractClassificationMetadata(JsonNode headers) {
        if (headers == null || !headers.isArray()) {
            return "{}";
        }
        Map<String, String> relevantHeaders = new java.util.LinkedHashMap<>();
        for (JsonNode header : headers) {
            String name = header.path("name").asText("");
            if (List.of("Reply-To", "Auto-Submitted", "Precedence", "List-Id", "List-Unsubscribe",
                    "In-Reply-To", "References", "Content-Type").stream()
                    .anyMatch(allowed -> allowed.equalsIgnoreCase(name))) {
                relevantHeaders.put(name, header.path("value").asText(""));
            }
        }
        try {
            return objectMapper.writeValueAsString(relevantHeaders);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize email classification metadata", ex);
        }
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
