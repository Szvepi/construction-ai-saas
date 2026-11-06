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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GmailInboxSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailInboxSyncService.class);
    private static final int MAX_MESSAGES_PER_REQUEST = 50;
    private static final String GMAIL_BASE_URL = "https://www.googleapis.com/gmail/v1/users/me";
    private static final String GMAIL_MESSAGES_URL = GMAIL_BASE_URL + "/messages";

    private final GmailHistoryStateStore historyStateStore;
    private final GmailTokenProvider tokenProvider;
    private final EmailService emailService;
    private final EmailRepository emailRepository;
    private final GmailSyncWindow syncWindow;
    private final GmailApiClient gmailApiClient;
    private final ObjectMapper objectMapper;
    private final GmailRetryPolicy retryPolicy;
    private final Clock clock;

    public GmailInboxSyncService(
            GmailHistoryStateStore historyStateStore,
            GmailTokenProvider tokenProvider,
            EmailService emailService,
            EmailRepository emailRepository,
            GmailSyncWindow syncWindow,
            GmailApiClient gmailApiClient,
            ObjectMapper objectMapper,
            GmailRetryPolicy retryPolicy,
            Clock clock) {
        this.historyStateStore = historyStateStore;
        this.tokenProvider = tokenProvider;
        this.emailService = emailService;
        this.emailRepository = emailRepository;
        this.syncWindow = syncWindow;
        this.gmailApiClient = gmailApiClient;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    public BackfillResponse backfill(Long userId) {
        RefreshEmailsResponse response = refresh(userId);
        return new BackfillResponse(
                response.fetchedCount(), response.processedCount(), response.hasMore(),
                response.complete(), response.inProgress() || response.waiting(), response.message());
    }

    public RefreshEmailsResponse refresh(Long userId) {
        return refresh(userId, true);
    }

    public RefreshEmailsResponse resumeForScheduler(Long userId) {
        return refresh(userId, false);
    }

    private RefreshEmailsResponse refresh(Long userId, boolean allowManualRetry) {
        GmailHistoryStateStore.Claim claim = allowManualRetry
                ? historyStateStore.claim(userId)
                : historyStateStore.claim(userId, false);
        String trigger = allowManualRetry ? "MANUAL" : "SCHEDULER";
        String syncType = syncType(claim);
        if (claim.authenticationRequired()) {
            log.debug("SYNC_SKIPPED event=SYNC_SKIPPED connectionId={} reason=RECONNECT_REQUIRED trigger={}",
                    claim.connectionId(), trigger);
            return reconnectRequiredResponse();
        }
        log.info(
                "SYNC_STARTED event=SYNC_STARTED connectionId={} syncType={} trigger={} batchId={} phase={} "
                        + "pageTokenPresent={} messageOffset={} pendingMessageCount={} retryAttempt={}",
                claim.connectionId(), syncType, trigger, claim.lockId(), phaseName(claim.phase()),
                hasText(claim.pageToken()), claim.messageOffset(), claim.pendingMessageIds().size(),
                claim.retryAttempts() + 1);
        if (claim.retryAt() != null && claim.retryAt().isAfter(clock.instant())) {
            log.info("SYNC_RETRY_SCHEDULED event=SYNC_RETRY_SCHEDULED connectionId={} syncType={} "
                            + "batchId={} retryAt={} retryAttempt={}",
                    claim.connectionId(), syncType, claim.lockId(), claim.retryAt(), claim.retryAttempts());
            return waitingResponse(claim.phase(), claim.retryAt());
        }
        if (claim.retryExhausted()) {
            log.error("SYNC_FAILED event=SYNC_FAILED connectionId={} syncType={} batchId={} "
                            + "exceptionCategory=RETRY_EXHAUSTED",
                    claim.connectionId(), syncType, claim.lockId());
            return refreshResponse(0, 0, false, false, false, "FAILED",
                    "A Gmail-szinkron automatikus folytatása nem ütemezhető. Indítsd újra a frissítést.");
        }
        if (claim.status() == GmailHistoryStateStore.Status.IN_PROGRESS) {
            log.info("SYNC_RESUMED event=SYNC_RESUMED connectionId={} syncType={} batchId={} "
                            + "phase={} reason=ACTIVE_LEASE",
                    claim.connectionId(), syncType, claim.lockId(), phaseName(claim.phase()));
            return refreshResponse(0, 0, true, false, true, claim.phase(),
                    "A Gmail-frissítés már folyamatban van.");
        }

        try {
            String accessToken = accessToken(claim.connectionId());
            String phase = claim.phase();
            String cursor = claim.cursor();
            String baselineId = claim.baselineId();
            Instant cutoff = claim.cutoff();
            String query = claim.query();
            String pageToken = claim.pageToken();
            int retryAttempt = claim.retryAttempts() + 1;
            if (phase != null || pageToken != null || claim.messageOffset() > 0) {
                log.info("SYNC_RESUMED event=SYNC_RESUMED connectionId={} syncType={} trigger={} batchId={} "
                                + "phase={} pageTokenPresent={} messageOffset={} pendingMessageCount={}",
                        claim.connectionId(), syncType, trigger, claim.lockId(), phaseName(phase),
                        hasText(pageToken), claim.messageOffset(), claim.pendingMessageIds().size());
            }

            if (phase != null
                    && !java.util.Objects.equals(claim.policyMonths(), syncWindow.configuredMonths())) {
                baselineId = getProfileHistoryId(accessToken, claim.connectionId(), retryAttempt);
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
                    baselineId = getProfileHistoryId(accessToken, claim.connectionId(), retryAttempt);
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
                page = listHistory(accessToken, historyStartId, pageToken, claim, retryAttempt);
            } catch (GmailBackfillException ex) {
                if ("HISTORY_ID_EXPIRED".equals(ex.getCode())) {
                    String recoveryBaseline = getProfileHistoryId(accessToken, claim.connectionId(), retryAttempt);
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
                    page = listHistory(accessToken, historyStartId, null, claim, retryAttempt);
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
            if (ex instanceof GmailRateLimitDeferredException deferred) {
                return deferSync(claim, deferred.getRetryAt(), null, deferred.getReason());
            }
            if (ex instanceof GmailApiException apiException
                    && (apiException.isTransientRateLimit() || apiException.isTransientServerError())) {
                return deferSync(claim, apiException.getRetryAt(), apiException.getRetryAt(), apiException.getReason());
            }
            if (ex instanceof GmailBackfillException gmailException
                    && "GMAIL_AUTHENTICATION_REQUIRED".equals(gmailException.getCode())) {
                String category = ex instanceof GmailAuthenticationRequiredException authException
                        ? safeLogValue(authException.getReason()) : "UNKNOWN";
                boolean transition = historyStateStore.markAuthenticationRequired(
                        claim.connectionId(), claim.lockId(), category);
                if (transition) {
                    log.warn("SYNC_RECONNECT_REQUIRED event=SYNC_RECONNECT_REQUIRED connectionId={} "
                                    + "syncType={} exceptionCategory=AUTHENTICATION_REQUIRED reason={} at={}",
                            claim.connectionId(), syncType, category, clock.instant());
                }
                return reconnectRequiredResponse();
            }
            logSyncFailure(claim, syncType, ex);
            releaseHistory(claim.connectionId(), claim.lockId(), ex);
            throw ex;
        }
    }

    private RefreshEmailsResponse deferSync(
            GmailHistoryStateStore.Claim claim,
            Instant limiterRetryAt,
            Instant serverRetryAt,
            String reason) {
        int nextAttempt = claim.retryAttempts() + 1;
        int retryDelayAttempt = nextAttempt >= retryPolicy.maxAttempts()
                ? retryPolicy.maxAttempts() + 1
                : nextAttempt;
        Instant retryAt = retryPolicy.retryAt(
                retryDelayAttempt,
                serverRetryAt == null ? limiterRetryAt : serverRetryAt,
                clock.instant());
        historyStateStore.defer(
                claim.connectionId(), claim.lockId(), retryAt, retryPolicy.maxAttempts());
        log.warn("SYNC_RETRY_SCHEDULED event=SYNC_RETRY_SCHEDULED connectionId={} syncType={} batchId={} "
                        + "reason={} retryAttempt={} retryAt={} phase={}",
                claim.connectionId(), syncType(claim), claim.lockId(), safeLogValue(reason),
                nextAttempt, retryAt, phaseName(claim.phase()));
        if (nextAttempt >= retryPolicy.maxAttempts()) {
            log.error("SYNC_FAILED event=SYNC_FAILED connectionId={} syncType={} batchId={} "
                            + "exceptionCategory=RETRY_EXHAUSTED retryAttempt={} nextRetryAt={}",
                    claim.connectionId(), syncType(claim), claim.lockId(), nextAttempt, retryAt);
            return new RefreshEmailsResponse(
                    0, 0, true, false, false, "WAITING",
                    "A Gmail átmenetileg továbbra is korlátoz. A szinkron késleltetve automatikusan folytatódik.",
                    true, retryAt.atOffset(ZoneOffset.UTC), false);
        }
        return waitingResponse(claim.phase(), retryAt);
    }

    private RefreshEmailsResponse reconnectRequiredResponse() {
        return new RefreshEmailsResponse(
                0, 0, false, false, false, "AUTH_REQUIRED",
                "A Gmail-kapcsolat lejárt vagy megszakadt. Csatlakoztasd újra a levelezést a szinkronizáció folytatásához.",
                false, null, true);
    }

    private RefreshEmailsResponse waitingResponse(String phase, Instant retryAt) {
        return new RefreshEmailsResponse(
                0, 0, true, false, false, phase == null ? "WAITING" : phase,
                "A Gmail-szinkron átmenetileg várakozik, automatikusan folytatódik.",
                true, retryAt == null ? null : retryAt.atOffset(ZoneOffset.UTC), false);
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
            page = listMessages(accessToken, query, pageToken, claim, phase, claim.retryAttempts() + 1);
        } catch (GmailBackfillException ex) {
            if (!"INVALID_PAGE_TOKEN".equals(ex.getCode()) || pageToken == null) {
                throw ex;
            }
            log.warn("Gmail {} scan page token expired for connection {}; restarting the pinned query",
                    phase, claim.connectionId());
            page = listMessages(accessToken, query, null, claim, phase, claim.retryAttempts() + 1);
        }
        List<Email> stagedEmails = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        for (String messageId : page.messageIds()) {
            historyStateStore.renew(claim.connectionId(), claim.lockId());
            if (emailRepository.existsByGmailConnectionIdAndGmailMessageId(claim.connectionId(), messageId)) {
                skipped++;
                logMessageSkipped(claim, phase, "ALREADY_IMPORTED");
                continue;
            }
            try {
                Optional<Email> email = fetchEmail(accessToken, messageId, cutoff, true,
                        claim.connectionId(), claim.retryAttempts() + 1);
                if (email.isPresent()) {
                    stagedEmails.add(email.get());
                } else {
                    skipped++;
                    logMessageSkipped(claim, phase, "OUTSIDE_CUTOFF_OR_LABEL_FILTER");
                }
                if (stagedEmails.size() >= 10) {
                    imported += historyStateStore.persistPartialEmails(
                            claim.connectionId(), claim.lockId(), stagedEmails);
                    stagedEmails.clear();
                }
            } catch (GmailBackfillException ex) {
                if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                    skipped++;
                    logMessageSkipped(claim, phase, "MESSAGE_NO_LONGER_EXISTS");
                    continue;
                }
                logMessageFailure(claim, phase, ex);
                throw ex;
            } catch (GmailRateLimitDeferredException ex) {
                throw ex;
            } catch (GmailApiException ex) {
                if (!ex.isTransientRateLimit() && !ex.isTransientServerError()) {
                    logMessageFailure(claim, phase, ex);
                }
                throw ex;
            } catch (RuntimeException ex) {
                logMessageFailure(claim, phase, ex);
                throw ex;
            }
        }
        imported += historyStateStore.completeScanPage(
                claim.connectionId(), claim.lockId(), stagedEmails, page.nextPageToken());
        boolean more = page.nextPageToken() != null && !page.nextPageToken().isBlank();
        log.info("SYNC_PAGE_SAVED event=SYNC_PAGE_SAVED connectionId={} syncType={} batchId={} "
                        + "phase={} returnedCount={} importedCount={} skippedCount={} failedCount=0 "
                        + "nextPageExists={} persistedCursorPresent={} messageOffset=0",
                claim.connectionId(), syncType(claim), claim.lockId(), phaseName(phase),
                page.messageIds().size(), imported, skipped, more, more);
        if (!more) {
            log.info("SYNC_COMPLETED event=SYNC_COMPLETED connectionId={} syncType={} batchId={} "
                            + "completionReason=NO_NEXT_PAGE_TOKEN completedScope=SIX_MONTH_INITIAL_IMPORT",
                    claim.connectionId(), syncType(claim), claim.lockId());
        }
        return refreshResponse(
                imported,
                page.messageIds().size(),
                more,
                !more,
                false,
                more ? phase : "INITIAL_COMPLETE",
                more
                        ? "A Gmail-postafiók hat hónapos kezdeti betöltése folyamatban van."
                        : "Az elmúlt hat hónap kezdeti importja befejeződött; a Gmail-változások követése folytatódik.");
    }

    private RefreshEmailsResponse processPendingHistoryBatch(
            GmailHistoryStateStore.Claim claim,
            String accessToken) {
        List<String> ids = claim.pendingMessageIds();
        int start = claim.messageOffset();
        int end = Math.min(start + MAX_MESSAGES_PER_REQUEST, ids.size());
        List<Email> stagedEmails = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        for (int index = start; index < end; index++) {
            String messageId = ids.get(index);
            historyStateStore.renew(claim.connectionId(), claim.lockId());
            if (emailRepository.existsByGmailConnectionIdAndGmailMessageId(claim.connectionId(), messageId)) {
                skipped++;
                logMessageSkipped(claim, "HISTORY", "ALREADY_IMPORTED");
                continue;
            }
            try {
                Optional<Email> email = fetchEmail(accessToken, messageId, claim.cutoff(), true,
                        claim.connectionId(), claim.retryAttempts() + 1);
                if (email.isPresent()) {
                    stagedEmails.add(email.get());
                } else {
                    skipped++;
                    logMessageSkipped(claim, "HISTORY", "OUTSIDE_CUTOFF_OR_LABEL_FILTER");
                }
                if (stagedEmails.size() >= 10) {
                    imported += historyStateStore.persistPartialEmails(
                            claim.connectionId(), claim.lockId(), stagedEmails);
                    stagedEmails.clear();
                }
            } catch (GmailBackfillException ex) {
                if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                    skipped++;
                    logMessageSkipped(claim, "HISTORY", "MESSAGE_NO_LONGER_EXISTS");
                    continue;
                }
                logMessageFailure(claim, "HISTORY", ex);
                throw ex;
            } catch (GmailRateLimitDeferredException ex) {
                throw ex;
            } catch (GmailApiException ex) {
                if (!ex.isTransientRateLimit() && !ex.isTransientServerError()) {
                    logMessageFailure(claim, "HISTORY", ex);
                }
                throw ex;
            } catch (RuntimeException ex) {
                logMessageFailure(claim, "HISTORY", ex);
                throw ex;
            }
        }
        imported += historyStateStore.completeHistoryBatch(
                claim.connectionId(), claim.lockId(), stagedEmails, end - start);

        boolean candidatesRemain = end < ids.size();
        boolean moreHistoryPages = !candidatesRemain
                && claim.pendingPageToken() != null
                && !claim.pendingPageToken().isBlank();
        boolean complete = !candidatesRemain && !moreHistoryPages;
        log.info("SYNC_PAGE_SAVED event=SYNC_PAGE_SAVED connectionId={} syncType=HISTORY batchId={} "
                        + "pageTokenPresent={} returnedCount={} importedCount={} skippedCount={} failedCount=0 "
                        + "nextPageExists={} persistedCursorPresent={} messageOffset={} pendingMessageCount={}",
                claim.connectionId(), claim.lockId(), hasText(claim.pendingPageToken()),
                end - start, imported, skipped, hasText(claim.pendingPageToken()), moreHistoryPages,
                end, ids.size());
        String message = complete
                ? (imported == 0
                        ? "Az aktuális Gmail-frissítés során nem érkezett új levél."
                        : "A Gmail-frissítés befejeződött.")
                : "A Gmail-változások feldolgozása folytatódik.";
        if (complete) {
            log.info("SYNC_COMPLETED event=SYNC_COMPLETED connectionId={} syncType=HISTORY batchId={} "
                            + "completionReason=NO_PENDING_HISTORY_AND_NO_NEXT_PAGE",
                    claim.connectionId(), claim.lockId());
        }
        return refreshResponse(
                imported, end - start, !complete, complete, false, "HISTORY", message);
    }

    private GmailPage listMessages(
            String accessToken,
            String query,
            String pageToken,
            GmailHistoryStateStore.Claim claim,
            String phase,
            int retryAttempt) {
        Long connectionId = claim.connectionId();
        logGmailListRequest(claim, phase, query, pageToken);
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(GMAIL_MESSAGES_URL)
                .queryParam("maxResults", MAX_MESSAGES_PER_REQUEST)
                .queryParam("q", query);
        if (pageToken != null && !pageToken.isBlank()) {
            uri.queryParam("pageToken", pageToken);
        }

        JsonNode response = getJson(
                uri.build().encode().toUri(),
                accessToken,
                pageToken == null ? RequestKind.MESSAGE_LIST : RequestKind.MESSAGE_LIST_PAGE,
                connectionId,
                retryAttempt);
        List<String> messageIds = new ArrayList<>();
        JsonNode messages = response.path("messages");
        if (!messages.isMissingNode() && !messages.isNull() && !messages.isArray()) {
            throw invalidResponse(connectionId, "messages.list", retryAttempt, "MESSAGES_FIELD_SHAPE");
        }
        String nextPageToken = optionalText(response, "nextPageToken", connectionId, "messages.list", retryAttempt);
        JsonNode sizeEstimate = response.path("resultSizeEstimate");
        if ((messages.isMissingNode() || messages.isNull()) && !hasText(nextPageToken)
                && !(sizeEstimate.isIntegralNumber() && sizeEstimate.asLong() == 0)) {
            throw invalidResponse(connectionId, "messages.list", retryAttempt, "EMPTY_PAGE_SHAPE");
        }
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode idNode = message.path("id");
                if (!message.isObject() || !idNode.isTextual() || idNode.asText().isBlank()) {
                    throw invalidResponse(connectionId, "messages.list", retryAttempt, "MESSAGE_ID_MISSING");
                }
                messageIds.add(idNode.asText());
            }
        }
        log.info("GMAIL_PAGE_RECEIVED event=GMAIL_PAGE_RECEIVED connectionId={} syncType={} batchId={} "
                        + "phase={} operation=messages.list pageSequence={} returnedCount={} nextPageExists={} "
                        + "resultSizeEstimate={}",
                connectionId, syncType(claim), claim.lockId(), phaseName(phase),
                hasText(pageToken) ? "CONTINUATION" : "FIRST", messageIds.size(), hasText(nextPageToken),
                response.path("resultSizeEstimate").isIntegralNumber()
                        ? response.path("resultSizeEstimate").asLong()
                        : "unknown");
        return new GmailPage(messageIds, nextPageToken);
    }

    private HistoryPage listHistory(
            String accessToken,
            String cursor,
            String pageToken,
            GmailHistoryStateStore.Claim claim,
            int retryAttempt) {
        Long connectionId = claim.connectionId();
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
        log.info("GMAIL_PAGE_REQUESTED event=GMAIL_PAGE_REQUESTED connectionId={} syncType=HISTORY batchId={} "
                        + "phase=HISTORY operation=history.list pageSequence={} pageTokenPresent={} "
                        + "maxResults=100 historyCursorPresent={}",
                connectionId, claim.lockId(), hasText(pageToken) ? "CONTINUATION" : "FIRST",
                hasText(pageToken), hasText(cursor));
        JsonNode response = getJson(
                uri.build().encode().toUri(),
                accessToken,
                pageToken == null ? RequestKind.HISTORY_LIST : RequestKind.HISTORY_LIST_PAGE,
                connectionId,
                retryAttempt);

        Set<String> ids = new LinkedHashSet<>();
        JsonNode history = response.path("history");
        if (!history.isMissingNode() && !history.isNull() && !history.isArray()) {
            throw invalidResponse(connectionId, "history.list", retryAttempt, "HISTORY_FIELD_SHAPE");
        }
        if (history.isArray()) {
            for (JsonNode item : history) {
                if (!item.isObject()) {
                    throw invalidResponse(connectionId, "history.list", retryAttempt, "HISTORY_ENTRY_SHAPE");
                }
                addEventMessageIds(ids, item.path("messagesAdded"), connectionId, retryAttempt);
                addEventMessageIds(ids, item.path("labelsAdded"), connectionId, retryAttempt);
                addEventMessageIds(ids, item.path("labelsRemoved"), connectionId, retryAttempt);
            }
        }
        String responseHistoryId = optionalText(
                response, "historyId", connectionId, "history.list", retryAttempt);
        if (responseHistoryId == null || responseHistoryId.isBlank()) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY,
                    "GMAIL_INVALID_RESPONSE",
                    "A Gmail-változásválasz nem tartalmaz historyId értéket.");
        }
        String nextPageToken = optionalText(response, "nextPageToken", connectionId, "history.list", retryAttempt);
        log.info("GMAIL_PAGE_RECEIVED event=GMAIL_PAGE_RECEIVED connectionId={} syncType=HISTORY batchId={} "
                        + "operation=history.list pageSequence={} returnedCount={} nextPageExists={} historyIdPresent={}",
                connectionId, claim.lockId(), hasText(pageToken) ? "CONTINUATION" : "FIRST",
                ids.size(), hasText(nextPageToken), true);
        return new HistoryPage(List.copyOf(ids), nextPageToken, responseHistoryId);
    }

    private void addEventMessageIds(Set<String> ids, JsonNode events, Long connectionId, int retryAttempt) {
        if (events.isMissingNode() || events.isNull()) {
            return;
        }
        if (!events.isArray()) {
            throw invalidResponse(connectionId, "history.list", retryAttempt, "HISTORY_EVENT_FIELD_SHAPE");
        }
        for (JsonNode event : events) {
            JsonNode message = event.path("message");
            JsonNode id = message.path("id");
            if (!event.isObject() || !message.isObject() || !id.isTextual() || id.asText().isBlank()) {
                throw invalidResponse(connectionId, "history.list", retryAttempt, "HISTORY_MESSAGE_ID_MISSING");
            }
            ids.add(id.asText());
        }
    }

    private String getProfileHistoryId(String accessToken, Long connectionId, int retryAttempt) {
        JsonNode profile = getJson(
                URI.create(GMAIL_BASE_URL + "/profile"), accessToken, RequestKind.PROFILE,
                connectionId, retryAttempt);
        String historyId = optionalText(profile, "historyId", connectionId, "profile.get", retryAttempt);
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
            boolean requirePrimaryInbox,
            Long connectionId,
            int retryAttempt) {
        URI fullUri = UriComponentsBuilder.fromUriString(GMAIL_MESSAGES_URL)
                .pathSegment(messageId)
                .queryParam("format", "full")
                .build()
                .encode()
                .toUri();
        JsonNode fullMessage = getJson(fullUri, accessToken, RequestKind.MESSAGE_FULL, connectionId, retryAttempt);
        Instant internalDate = messageInstant(fullMessage);
        if (!syncWindow.isWithinCurrentAndPinnedWindow(internalDate, pinnedCutoff)) {
            return Optional.empty();
        }
        JsonNode labels = fullMessage.path("labelIds");
        if (requirePrimaryInbox && !isPrimaryInboxMessage(labels)) {
            return Optional.empty();
        }

        return Optional.of(emailService.createEmailFromGmailResponse(
                null,
                messageId,
                fullMessage.path("payload").path("headers"),
                fullMessage,
                EmailCategory.OTHER));
    }

    private Instant messageInstant(JsonNode message) {
        JsonNode dateNode = message.path("internalDate");
        if (!dateNode.isTextual() || dateNode.asText().isBlank()) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY,
                    "GMAIL_INVALID_RESPONSE",
                    "A Gmail-üzenet nem tartalmaz érvényes belső dátumot.");
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(dateNode.asText()));
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

    private String accessToken(Long connectionId) {
        return tokenProvider.accessToken(connectionId);
    }

    private JsonNode getJson(
            URI uri, String accessToken, RequestKind requestKind, Long connectionId, int retryAttempt) {
        try {
            ResponseEntity<String> response = gmailApiClient.get(
                    connectionId, accessToken, uri, requestKind.operation(), retryAttempt);
            JsonNode body = objectMapper.readTree(response.getBody());
            if (body == null || !body.isObject()) {
                throw invalidResponse(connectionId, requestKind.operation().name(), retryAttempt, "ROOT_OBJECT_MISSING");
            }
            return body;
        } catch (GmailApiException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatus());
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
            if (status == HttpStatus.NOT_FOUND && requestKind == RequestKind.MESSAGE_FULL) {
                throw new GmailBackfillException(status, "MESSAGE_NOT_FOUND", "A Gmail-üzenet már nem érhető el.");
            }
            if (ex.isTransientRateLimit()) {
                throw ex;
            }
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                throw new GmailAuthenticationRequiredException(
                        status.value() + "_" + safeLogValue(ex.getReason()),
                        "A Gmail-hitelesítés lejárt vagy nem rendelkezik a szükséges hozzáféréssel. Csatlakoztasd újra a Gmail-fiókot.");
            }
            if (status.is5xxServerError()) {
                throw ex;
            }
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY, "GMAIL_API_ERROR", "A Gmail API nem tudta feldolgozni a kérést.");
        } catch (ResourceAccessException ex) {
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY, "GMAIL_TEMPORARILY_UNAVAILABLE",
                    "A Gmail nem válaszolt időben. Próbáld újra.");
        } catch (GmailBackfillException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            log.error("GMAIL_RESPONSE_INVALID event=GMAIL_RESPONSE_INVALID connectionId={} operation={} "
                            + "upstreamHttpStatus=200 retryAttempt={} exceptionCategory=INVALID_JSON exceptionType={}",
                    connectionId, requestKind.operation(), retryAttempt, ex.getClass().getSimpleName());
            throw new GmailBackfillException(
                    HttpStatus.BAD_GATEWAY, "GMAIL_INVALID_RESPONSE", "A Gmail API érvénytelen JSON-választ adott.");
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

    private void logGmailListRequest(
            GmailHistoryStateStore.Claim claim, String phase, String query, String pageToken) {
        log.info("GMAIL_PAGE_REQUESTED event=GMAIL_PAGE_REQUESTED connectionId={} syncType={} batchId={} "
                        + "phase={} operation=messages.list pageSequence={} pageTokenPresent={} maxResults={} "
                        + "filterType=INBOX_PRIMARY labelFilter=INBOX query=\"{}\" cutoffEpoch={}",
                claim.connectionId(), syncType(claim), claim.lockId(), phaseName(phase),
                hasText(pageToken) ? "CONTINUATION" : "FIRST", hasText(pageToken),
                MAX_MESSAGES_PER_REQUEST, sanitizeQuery(query), cutoffEpoch(query));
    }

    private String sanitizeQuery(String query) {
        if (query == null) {
            return "unknown";
        }
        return query.replaceAll("(?i)(from|to|cc|bcc|subject|deliveredto|list):(\"[^\"]*\"|[^\\s]+)",
                        "$1:[REDACTED]")
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[REDACTED]")
                .replaceAll("[\\r\\n\"]", " ");
    }

    private String optionalText(
            JsonNode object, String field, Long connectionId, String operation, int retryAttempt) {
        JsonNode value = object.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalidResponse(
                    connectionId, operation, retryAttempt, field.toUpperCase(Locale.ROOT) + "_FIELD_SHAPE");
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private long cutoffEpoch(String query) {
        if (query == null) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|\\s)after:(\\d+)")
                .matcher(query);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String syncType(GmailHistoryStateStore.Claim claim) {
        if ("HISTORY".equals(claim.phase()) || (claim.phase() == null && hasText(claim.cursor()))) {
            return "HISTORY";
        }
        if ("RECOVERY_SCAN".equals(claim.phase()) || hasText(claim.cursor())) {
            return "BACKFILL";
        }
        return "INITIAL";
    }

    private String phaseName(String phase) {
        return phase == null || phase.isBlank() ? "UNINITIALIZED" : phase;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void logMessageSkipped(GmailHistoryStateStore.Claim claim, String phase, String reason) {
        log.info("GMAIL_MESSAGE_SKIPPED event=GMAIL_MESSAGE_SKIPPED connectionId={} syncType={} "
                        + "batchId={} phase={} reason={}",
                claim.connectionId(), syncType(claim), claim.lockId(), phaseName(phase), reason);
    }

    private void logMessageFailure(GmailHistoryStateStore.Claim claim, String phase, RuntimeException failure) {
        Integer upstreamStatus = null;
        Integer appStatus = null;
        String reason = "UNKNOWN";
        String exceptionCategory = failure.getClass().getSimpleName();
        if (failure instanceof GmailApiException apiException) {
            upstreamStatus = apiException.getStatus();
            reason = apiException.getReason();
        } else if (failure instanceof GmailBackfillException backfillException) {
            appStatus = backfillException.getStatus().value();
            if ("GMAIL_INVALID_RESPONSE".equals(backfillException.getCode())) {
                upstreamStatus = 200;
            }
            reason = backfillException.getCode();
            exceptionCategory = backfillException.getCode();
        }
        log.error("GMAIL_MESSAGE_FAILED event=GMAIL_MESSAGE_FAILED connectionId={} syncType={} "
                        + "batchId={} phase={} upstreamHttpStatus={} appHttpStatus={} googleReason={} "
                        + "retryAttempt={} failedCount=1 exceptionCategory={}",
                claim.connectionId(), syncType(claim), claim.lockId(), phaseName(phase),
                upstreamStatus == null ? "unknown" : upstreamStatus,
                appStatus == null ? "unknown" : appStatus,
                safeLogValue(reason), claim.retryAttempts() + 1, safeLogValue(exceptionCategory));
    }

    private void logSyncFailure(
            GmailHistoryStateStore.Claim claim, String syncType, RuntimeException failure) {
        Integer upstreamStatus = null;
        Integer appStatus = null;
        String reason = "UNKNOWN";
        String exceptionCategory = failure.getClass().getSimpleName();
        if (failure instanceof GmailApiException apiException) {
            upstreamStatus = apiException.getStatus();
            reason = apiException.getReason();
        } else if (failure instanceof GmailBackfillException backfillException) {
            appStatus = backfillException.getStatus().value();
            if ("GMAIL_INVALID_RESPONSE".equals(backfillException.getCode())) {
                upstreamStatus = 200;
            }
            reason = backfillException.getCode();
            exceptionCategory = backfillException.getCode();
        }
        log.error("SYNC_FAILED event=SYNC_FAILED connectionId={} syncType={} batchId={} phase={} "
                        + "upstreamHttpStatus={} appHttpStatus={} googleReason={} retryAttempt={} "
                        + "failedCount=1 exceptionCategory={}",
                claim.connectionId(), syncType, claim.lockId(), phaseName(claim.phase()),
                upstreamStatus == null ? "unknown" : upstreamStatus,
                appStatus == null ? "unknown" : appStatus,
                safeLogValue(reason), claim.retryAttempts() + 1, safeLogValue(exceptionCategory));
    }

    private GmailBackfillException invalidResponse(
            Long connectionId, String operation, int retryAttempt, String reason) {
        log.error("GMAIL_RESPONSE_INVALID event=GMAIL_RESPONSE_INVALID connectionId={} operation={} "
                        + "upstreamHttpStatus=200 retryAttempt={} exceptionCategory=INVALID_RESPONSE responsePart={}",
                connectionId, operation, retryAttempt, reason);
        return new GmailBackfillException(
                HttpStatus.BAD_GATEWAY, "GMAIL_INVALID_RESPONSE", "A Gmail API érvénytelen választ adott.");
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
        PROFILE(GmailApiOperation.PROFILE_GET),
        MESSAGE_FULL(GmailApiOperation.MESSAGES_GET_FULL),
        MESSAGE_LIST(GmailApiOperation.MESSAGES_LIST),
        MESSAGE_LIST_PAGE(GmailApiOperation.MESSAGES_LIST),
        HISTORY_LIST(GmailApiOperation.HISTORY_LIST),
        HISTORY_LIST_PAGE(GmailApiOperation.HISTORY_LIST);

        private final GmailApiOperation operation;

        RequestKind(GmailApiOperation operation) {
            this.operation = operation;
        }

        GmailApiOperation operation() {
            return operation;
        }
    }

    private record GmailPage(List<String> messageIds, String nextPageToken) {
    }

    private record HistoryPage(List<String> messageIds, String nextPageToken, String historyId) {
    }
}
