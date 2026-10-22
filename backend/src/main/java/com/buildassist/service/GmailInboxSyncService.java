package com.buildassist.service;

import com.buildassist.dto.EmailDtos.BackfillResponse;
import com.buildassist.dto.EmailDtos.RefreshEmailsResponse;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.repository.EmailRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GmailInboxSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailInboxSyncService.class);
    private static final int MAX_MESSAGES_PER_REQUEST = 50;
    private static final String GMAIL_BASE_URL = "https://www.googleapis.com/gmail/v1/users/me";
    private static final String GMAIL_MESSAGES_URL = GMAIL_BASE_URL + "/messages";

    private final GmailBackfillStateStore backfillStateStore;
    private final GmailHistoryStateStore historyStateStore;
    private final GmailService gmailService;
    private final EmailService emailService;
    private final EmailRepository emailRepository;
    private final GmailSyncWindow syncWindow;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GmailInboxSyncService(
            GmailBackfillStateStore backfillStateStore,
            GmailHistoryStateStore historyStateStore,
            GmailService gmailService,
            EmailService emailService,
            EmailRepository emailRepository,
            GmailSyncWindow syncWindow,
            @Qualifier("gmailRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.backfillStateStore = backfillStateStore;
        this.historyStateStore = historyStateStore;
        this.gmailService = gmailService;
        this.emailService = emailService;
        this.emailRepository = emailRepository;
        this.syncWindow = syncWindow;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public BackfillResponse backfill(Long userId) {
        GmailBackfillStateStore.Claim claim = backfillStateStore.claim(userId);
        if (claim.status() == GmailBackfillStateStore.ClaimStatus.COMPLETE) {
            return new BackfillResponse(0, 0, false, true, false, "Nincs több betölthető levél.");
        }
        if (claim.status() == GmailBackfillStateStore.ClaimStatus.IN_PROGRESS) {
            return new BackfillResponse(0, 0, true, false, true, "A régebbi levelek betöltése már folyamatban van.");
        }

        try {
            String accessToken = accessToken(claim.gmailAddress());
            GmailPage page;
            try {
                page = listMessages(accessToken, claim.query(), claim.pageToken());
            } catch (GmailBackfillException ex) {
                if (!"INVALID_PAGE_TOKEN".equals(ex.getCode()) || claim.pageToken() == null) {
                    throw ex;
                }
                log.warn("Gmail backfill page token expired for connection {}; restarting in bounded query",
                        claim.connectionId());
                page = listMessages(accessToken, claim.query(), null);
            }

            List<Email> stagedEmails = new ArrayList<>();
            for (String messageId : page.messageIds()) {
                backfillStateStore.renew(claim.connectionId(), claim.lockId());
                if (emailRepository.existsByGmailConnectionIdAndGmailMessageId(claim.connectionId(), messageId)) {
                    continue;
                }
                try {
                    fetchEmail(accessToken, messageId, claim.cutoff(), false).ifPresent(stagedEmails::add);
                } catch (GmailBackfillException ex) {
                    if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                        log.info("Skipping Gmail message {} because it no longer exists", messageId);
                        continue;
                    }
                    throw ex;
                }
            }

            int importedCount = backfillStateStore.completePage(
                    claim.connectionId(), claim.lockId(), stagedEmails, page.nextPageToken());
            boolean hasMore = page.nextPageToken() != null && !page.nextPageToken().isBlank();
            return new BackfillResponse(
                    importedCount,
                    page.messageIds().size(),
                    hasMore,
                    !hasMore,
                    false,
                    hasMore ? "A levéladag feldolgozása befejeződött." : "Nincs több betölthető Gmail-levél.");
        } catch (RuntimeException ex) {
            releaseBackfill(claim.connectionId(), claim.lockId(), ex);
            throw ex;
        }
    }

    public RefreshEmailsResponse refresh(Long userId) {
        GmailHistoryStateStore.Claim claim = historyStateStore.claim(userId);
        if (claim.status() == GmailHistoryStateStore.Status.IN_PROGRESS) {
            return refreshResponse(0, 0, true, false, true, claim.phase(),
                    "A Gmail-frissítés már folyamatban van.");
        }

        try {
            String accessToken = accessToken(claim.gmailAddress());
            String phase = claim.phase();
            String cursor = claim.cursor();
            String baselineId = claim.baselineId();
            Instant cutoff = claim.cutoff();
            String query = claim.query();
            String pageToken = claim.pageToken();

            if (phase != null
                    && !java.util.Objects.equals(claim.policyMonths(), syncWindow.configuredMonths())) {
                baselineId = getProfileHistoryId(accessToken);
                cutoff = syncWindow.currentCutoff();
                query = syncWindow.queryFor(cutoff);
                historyStateStore.beginScan(
                        claim.connectionId(), claim.lockId(), baselineId, cutoff, query, true);
                phase = "RECOVERY_SCAN";
                pageToken = null;
            }

            if (phase == null) {
                cutoff = syncWindow.currentCutoff();
                query = syncWindow.queryFor(cutoff);
                if (cursor == null
                        || cursor.isBlank()
                        || !java.util.Objects.equals(claim.policyMonths(), syncWindow.configuredMonths())) {
                    baselineId = getProfileHistoryId(accessToken);
                    boolean recovery = cursor != null && !cursor.isBlank();
                    historyStateStore.beginScan(
                            claim.connectionId(), claim.lockId(), baselineId, cutoff, query, recovery);
                    phase = recovery ? "RECOVERY_SCAN" : "BOOTSTRAP";
                    pageToken = null;
                } else {
                    historyStateStore.beginHistoryCycle(claim.connectionId(), claim.lockId(), cutoff, query);
                    phase = "HISTORY";
                    pageToken = null;
                }
            }

            if ("BOOTSTRAP".equals(phase) || "RECOVERY_SCAN".equals(phase)) {
                return processScanPage(claim, accessToken, query, cutoff, pageToken, phase);
            }
            if (!"HISTORY".equals(phase)) {
                throw new GmailBackfillException(
                        HttpStatus.CONFLICT, "HISTORY_STATE_INVALID", "A Gmail-szinkronizáció állapota érvénytelen.");
            }

            if (!claim.pendingMessageIds().isEmpty()
                    || claim.pendingHistoryId() != null
                    || claim.pendingPageToken() != null) {
                return processPendingHistoryBatch(claim, accessToken);
            }

            HistoryPage page;
            try {
                String historyStartId = baselineId == null || baselineId.isBlank() ? cursor : baselineId;
                page = listHistory(accessToken, historyStartId, pageToken);
            } catch (GmailBackfillException ex) {
                if ("HISTORY_ID_EXPIRED".equals(ex.getCode())) {
                    String recoveryBaseline = getProfileHistoryId(accessToken);
                    Instant recoveryCutoff = syncWindow.currentCutoff();
                    String recoveryQuery = syncWindow.queryFor(recoveryCutoff);
                    historyStateStore.beginScan(
                            claim.connectionId(),
                            claim.lockId(),
                            recoveryBaseline,
                            recoveryCutoff,
                            recoveryQuery,
                            true);
                    return processScanPage(
                            claim, accessToken, recoveryQuery, recoveryCutoff, null, "RECOVERY_SCAN");
                }
                if ("INVALID_HISTORY_PAGE_TOKEN".equals(ex.getCode()) && pageToken != null) {
                    historyStateStore.resetHistoryPage(claim.connectionId(), claim.lockId());
                    String historyStartId = baselineId == null || baselineId.isBlank() ? cursor : baselineId;
                    page = listHistory(accessToken, historyStartId, null);
                } else {
                    throw ex;
                }
            }

            historyStateStore.stageHistoryPage(
                    claim.connectionId(), claim.lockId(), page.messageIds(), page.nextPageToken(), page.historyId());
            GmailHistoryStateStore.Claim staged = new GmailHistoryStateStore.Claim(
                    claim.status(),
                    claim.lockId(),
                    claim.connectionId(),
                    claim.gmailAddress(),
                    cursor,
                    "HISTORY",
                    baselineId,
                    cutoff,
                    syncWindow.configuredMonths(),
                    query,
                    pageToken,
                    page.nextPageToken(),
                    page.historyId(),
                    page.messageIds(),
                    0);
            return processPendingHistoryBatch(staged, accessToken);
        } catch (RuntimeException ex) {
            releaseHistory(claim.connectionId(), claim.lockId(), ex);
            throw ex;
        }
    }

    private RefreshEmailsResponse processScanPage(
            GmailHistoryStateStore.Claim claim,
            String accessToken,
            String query,
            Instant cutoff,
            String pageToken,
            String phase) {
        GmailPage page;
        try {
            page = listMessages(accessToken, query, pageToken);
        } catch (GmailBackfillException ex) {
            if (!"INVALID_PAGE_TOKEN".equals(ex.getCode()) || pageToken == null) {
                throw ex;
            }
            log.warn("Gmail {} scan page token expired for connection {}; restarting the pinned query",
                    phase, claim.connectionId());
            page = listMessages(accessToken, query, null);
        }
        List<Email> stagedEmails = new ArrayList<>();
        for (String messageId : page.messageIds()) {
            historyStateStore.renew(claim.connectionId(), claim.lockId());
            if (emailRepository.existsByGmailConnectionIdAndGmailMessageId(claim.connectionId(), messageId)) {
                continue;
            }
            try {
                fetchEmail(accessToken, messageId, cutoff, true).ifPresent(stagedEmails::add);
            } catch (GmailBackfillException ex) {
                if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                    log.info("Skipping Gmail message {} because it no longer exists", messageId);
                    continue;
                }
                throw ex;
            }
        }
        int imported = historyStateStore.completeScanPage(
                claim.connectionId(), claim.lockId(), stagedEmails, page.nextPageToken());
        boolean more = page.nextPageToken() != null && !page.nextPageToken().isBlank();
        return refreshResponse(
                imported,
                page.messageIds().size(),
                true,
                false,
                false,
                more ? phase : "HISTORY",
                more ? "A Gmail-postafiók betöltése folytatódik." : "Az alaplista kész, a változások feldolgozása következik.");
    }

    private RefreshEmailsResponse processPendingHistoryBatch(
            GmailHistoryStateStore.Claim claim,
            String accessToken) {
        List<String> ids = claim.pendingMessageIds();
        int start = claim.messageOffset();
        int end = Math.min(start + MAX_MESSAGES_PER_REQUEST, ids.size());
        List<Email> stagedEmails = new ArrayList<>();
        for (int index = start; index < end; index++) {
            String messageId = ids.get(index);
            historyStateStore.renew(claim.connectionId(), claim.lockId());
            if (emailRepository.existsByGmailConnectionIdAndGmailMessageId(claim.connectionId(), messageId)) {
                continue;
            }
            try {
                fetchEmail(accessToken, messageId, claim.cutoff(), true).ifPresent(stagedEmails::add);
            } catch (GmailBackfillException ex) {
                if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                    log.info("Skipping Gmail history message {} because it no longer exists", messageId);
                    continue;
                }
                throw ex;
            }
        }
        int imported = historyStateStore.completeHistoryBatch(
                claim.connectionId(), claim.lockId(), stagedEmails, end - start);

        boolean candidatesRemain = end < ids.size();
        boolean moreHistoryPages = !candidatesRemain
                && claim.pendingPageToken() != null
                && !claim.pendingPageToken().isBlank();
        boolean complete = !candidatesRemain && !moreHistoryPages;
        String message = complete
                ? (imported == 0 ? "Nincs új Gmail-levél." : "A Gmail-frissítés befejeződött.")
                : "A Gmail-változások feldolgozása folytatódik.";
        return refreshResponse(
                imported, end - start, !complete, complete, false, "HISTORY", message);
    }

    private GmailPage listMessages(String accessToken, String query, String pageToken) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(GMAIL_MESSAGES_URL)
                .queryParam("maxResults", MAX_MESSAGES_PER_REQUEST)
                .queryParam("q", query);
        if (pageToken != null && !pageToken.isBlank()) {
            uri.queryParam("pageToken", pageToken);
        }

        JsonNode response = getJson(
                uri.build().encode().toUri(),
                accessToken,
                pageToken == null ? RequestKind.MESSAGE_LIST : RequestKind.MESSAGE_LIST_PAGE);
        List<String> messageIds = new ArrayList<>();
        JsonNode messages = response.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                String id = message.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    messageIds.add(id);
                }
            }
        }
        return new GmailPage(messageIds, response.path("nextPageToken").asText(null));
    }

    private HistoryPage listHistory(String accessToken, String cursor, String pageToken) {
        if (cursor == null || cursor.isBlank()) {
            throw new GmailBackfillException(
                    HttpStatus.CONFLICT, "HISTORY_CURSOR_MISSING", "A Gmail-szinkronizáció kurzora hiányzik.");
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(GMAIL_BASE_URL + "/history")
                .queryParam("startHistoryId", cursor)
                .queryParam("maxResults", 100);
        if (pageToken != null && !pageToken.isBlank()) {
            uri.queryParam("pageToken", pageToken);
        }
        JsonNode response = getJson(
                uri.build().encode().toUri(),
                accessToken,
                pageToken == null ? RequestKind.HISTORY_LIST : RequestKind.HISTORY_LIST_PAGE);

        Set<String> ids = new LinkedHashSet<>();
        JsonNode history = response.path("history");
        if (history.isArray()) {
            for (JsonNode item : history) {
                addEventMessageIds(ids, item.path("messagesAdded"));
                addEventMessageIds(ids, item.path("labelsAdded"));
                addEventMessageIds(ids, item.path("labelsRemoved"));
            }
        }
        String responseHistoryId = response.path("historyId").asText(null);
        if (responseHistoryId == null || responseHistoryId.isBlank()) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY,
                    "GMAIL_INVALID_RESPONSE",
                    "A Gmail-változásválasz nem tartalmaz historyId értéket.");
        }
        return new HistoryPage(
                List.copyOf(ids),
                response.path("nextPageToken").asText(null),
                responseHistoryId);
    }

    private void addEventMessageIds(Set<String> ids, JsonNode events) {
        if (!events.isArray()) {
            return;
        }
        for (JsonNode event : events) {
            String id = event.path("message").path("id").asText(null);
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
    }

    private String getProfileHistoryId(String accessToken) {
        JsonNode profile = getJson(
                URI.create(GMAIL_BASE_URL + "/profile"), accessToken, RequestKind.PROFILE);
        String historyId = profile.path("historyId").asText(null);
        if (historyId == null || historyId.isBlank()) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY, "GMAIL_INVALID_RESPONSE", "A Gmail-profil nem tartalmaz historyId értéket.");
        }
        return historyId;
    }

    private Optional<Email> fetchEmail(
            String accessToken,
            String messageId,
            Instant pinnedCutoff,
            boolean requirePrimaryInbox) {
        URI metadataUri = UriComponentsBuilder.fromUriString(GMAIL_MESSAGES_URL)
                .pathSegment(messageId)
                .queryParam("format", "metadata")
                .build()
                .encode()
                .toUri();
        JsonNode metadata = getJson(metadataUri, accessToken, RequestKind.MESSAGE);
        Instant internalDate = messageInstant(metadata);
        if (!syncWindow.isWithinCurrentAndPinnedWindow(internalDate, pinnedCutoff)) {
            return Optional.empty();
        }
        JsonNode labels = metadata.path("labelIds");
        if (requirePrimaryInbox && !isPrimaryInboxMessage(labels)) {
            return Optional.empty();
        }

        JsonNode headers = metadata.path("payload").path("headers");
        EmailCategory category = emailService.categorizeMessage(headers, messageId);
        if (category == EmailCategory.SPAM) {
            return Optional.of(emailService.createEmailFromGmailResponse(
                    null, messageId, headers, metadata, category));
        }

        URI fullUri = UriComponentsBuilder.fromUriString(GMAIL_MESSAGES_URL)
                .pathSegment(messageId)
                .queryParam("format", "full")
                .build()
                .encode()
                .toUri();
        JsonNode fullMessage = getJson(fullUri, accessToken, RequestKind.MESSAGE);
        return Optional.of(emailService.createEmailFromGmailResponse(
                null,
                messageId,
                fullMessage.path("payload").path("headers"),
                fullMessage,
                category));
    }

    private Instant messageInstant(JsonNode message) {
        String internalDate = message.path("internalDate").asText(null);
        if (internalDate == null || internalDate.isBlank()) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY,
                    "GMAIL_INVALID_RESPONSE",
                    "A Gmail-üzenet nem tartalmaz érvényes belső dátumot.");
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(internalDate));
        } catch (NumberFormatException ex) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY,
                    "GMAIL_INVALID_RESPONSE",
                    "A Gmail-üzenet belső dátuma érvénytelen.");
        }
    }

    private boolean isPrimaryInboxMessage(JsonNode labels) {
        if (!labels.isArray()) {
            return false;
        }
        Set<String> labelIds = new LinkedHashSet<>();
        labels.forEach(label -> labelIds.add(label.asText()));
        return labelIds.contains("INBOX")
                && labelIds.contains("CATEGORY_PERSONAL")
                && !labelIds.contains("CATEGORY_PROMOTIONS")
                && !labelIds.contains("CATEGORY_SOCIAL")
                && !labelIds.contains("CATEGORY_UPDATES")
                && !labelIds.contains("SPAM")
                && !labelIds.contains("TRASH");
    }

    private String accessToken(String gmailAddress) {
        return gmailService.getOAuth2AccessToken(gmailAddress)
                .orElseThrow(() -> new GmailBackfillException(
                        HttpStatus.FAILED_DEPENDENCY,
                        "GMAIL_AUTHENTICATION_REQUIRED",
                        "A Gmail-hitelesítés lejárt vagy nem érhető el. Csatlakoztasd újra a Gmail-fiókot."));
    }

    private JsonNode getJson(URI uri, String accessToken, RequestKind requestKind) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (body == null || !body.isObject()) {
                throw new GmailBackfillException(
                        HttpStatus.BAD_GATEWAY, "GMAIL_INVALID_RESPONSE", "A Gmail érvénytelen választ adott.");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.BAD_GATEWAY;
            }
            if (requestKind == RequestKind.MESSAGE_LIST_PAGE && status == HttpStatus.BAD_REQUEST) {
                throw new GmailBackfillException(
                        HttpStatus.BAD_REQUEST, "INVALID_PAGE_TOKEN",
                        "A Gmail-folytatási token érvénytelen; a betöltés újrakezdődik.");
            }
            if (requestKind == RequestKind.HISTORY_LIST_PAGE && status == HttpStatus.BAD_REQUEST) {
                throw new GmailBackfillException(
                        HttpStatus.BAD_REQUEST, "INVALID_HISTORY_PAGE_TOKEN",
                        "A Gmail változáslapozó tokenje lejárt.");
            }
            if ((requestKind == RequestKind.HISTORY_LIST || requestKind == RequestKind.HISTORY_LIST_PAGE)
                    && status == HttpStatus.NOT_FOUND) {
                throw new GmailBackfillException(
                        HttpStatus.CONFLICT, "HISTORY_ID_EXPIRED",
                        "A Gmail változáskurzora lejárt; helyreállító szinkron indul.");
            }
            if (status == HttpStatus.NOT_FOUND) {
                throw new GmailBackfillException(status, "MESSAGE_NOT_FOUND", "A Gmail-üzenet már nem érhető el.");
            }
            if ((status == HttpStatus.FORBIDDEN || status == HttpStatus.TOO_MANY_REQUESTS)
                    && isRateLimitError(ex.getResponseBodyAsString())) {
                throw new GmailBackfillException(
                        HttpStatus.TOO_MANY_REQUESTS, "GMAIL_RATE_LIMITED",
                        "A Gmail API kérési korlátja elérte a limitet. Várj egy kicsit, majd próbáld újra.");
            }
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                throw new GmailBackfillException(
                        HttpStatus.FAILED_DEPENDENCY, "GMAIL_AUTHENTICATION_REQUIRED",
                        "A Gmail-hitelesítés lejárt vagy nem rendelkezik a szükséges hozzáféréssel. Csatlakoztasd újra a Gmail-fiókot.");
            }
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                throw new GmailBackfillException(
                        HttpStatus.TOO_MANY_REQUESTS, "GMAIL_RATE_LIMITED",
                        "A Gmail API kérési korlátja elérte a limitet. Várj egy kicsit, majd próbáld újra.");
            }
            if (status.is5xxServerError()) {
                throw new GmailBackfillException(
                        HttpStatus.BAD_GATEWAY, "GMAIL_TEMPORARILY_UNAVAILABLE",
                        "A Gmail átmenetileg nem érhető el. Próbáld újra később.");
            }
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY, "GMAIL_API_ERROR", "A Gmail API nem tudta feldolgozni a kérést.");
        } catch (ResourceAccessException ex) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY, "GMAIL_TEMPORARILY_UNAVAILABLE",
                    "A Gmail nem válaszolt időben. Próbáld újra.");
        } catch (GmailBackfillException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY, "GMAIL_INVALID_RESPONSE", "A Gmail érvénytelen választ adott.");
        }
    }

    private boolean isRateLimitError(String responseBody) {
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            String reason = response.path("error").path("errors").path(0).path("reason").asText("");
            return reason.equals("rateLimitExceeded")
                    || reason.equals("userRateLimitExceeded")
                    || reason.equals("quotaExceeded");
        } catch (JsonProcessingException ex) {
            log.debug("Could not parse Gmail API error response to classify quota status", ex);
            return false;
        }
    }

    private RefreshEmailsResponse refreshResponse(
            int fetchedCount,
            int processedCount,
            boolean hasMore,
            boolean complete,
            boolean inProgress,
            String phase,
            String message) {
        return new RefreshEmailsResponse(
                fetchedCount, processedCount, hasMore, complete, inProgress, phase, message);
    }

    private void releaseBackfill(Long connectionId, java.util.UUID lockId, RuntimeException failure) {
        try {
            backfillStateStore.release(connectionId, lockId);
        } catch (RuntimeException releaseError) {
            failure.addSuppressed(releaseError);
            log.error("Failed to release Gmail backfill lock for connection {}", connectionId, releaseError);
        }
    }

    private void releaseHistory(Long connectionId, java.util.UUID lockId, RuntimeException failure) {
        try {
            historyStateStore.release(connectionId, lockId);
        } catch (RuntimeException releaseError) {
            failure.addSuppressed(releaseError);
            log.error("Failed to release Gmail history lock for connection {}", connectionId, releaseError);
        }
    }

    private enum RequestKind {
        PROFILE,
        MESSAGE,
        MESSAGE_LIST,
        MESSAGE_LIST_PAGE,
        HISTORY_LIST,
        HISTORY_LIST_PAGE
    }

    private record GmailPage(List<String> messageIds, String nextPageToken) {
    }

    private record HistoryPage(List<String> messageIds, String nextPageToken, String historyId) {
    }
}
