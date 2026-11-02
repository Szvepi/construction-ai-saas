package com.buildassist.service;

import com.buildassist.model.Email;
import com.buildassist.model.GmailConnection;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GmailHistoryStateStore {

    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final GmailConnectionRepository connections;
    private final EmailRepository emails;
    private final Clock clock;
    private final GmailSyncWindow syncWindow;

    public GmailHistoryStateStore(
            GmailConnectionRepository connections,
            EmailRepository emails,
            Clock clock,
            GmailSyncWindow syncWindow) {
        this.connections = connections;
        this.emails = emails;
        this.clock = clock;
        this.syncWindow = syncWindow;
    }

    @Transactional
    public Claim claim(Long userId) {
        return claim(userId, true);
    }

    @Transactional
    public Claim claim(Long userId, boolean allowManualRetry) {
        GmailConnection connection = connections.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new GmailBackfillException(
                        HttpStatus.CONFLICT, "GMAIL_NOT_CONNECTED", "Nincs csatlakoztatott Gmail-fiók."));
        Instant now = clock.instant();
        if (connection.isGmailSyncAuthRequired()) {
            return claim(connection, null, false, false, true);
        }
        if (connection.isGmailSyncRetryExhausted()) {
            if (allowManualRetry || connection.getGmailSyncRetryAt() == null
                    || !connection.getGmailSyncRetryAt().isAfter(now)) {
                connection.setGmailSyncRetryExhausted(false);
                connection.setGmailSyncRetryAttempts(0);
                connection.setGmailSyncRetryAt(null);
            } else {
                return claim(connection, null, false, true);
            }
        }
        if (connection.getGmailSyncRetryAt() != null && connection.getGmailSyncRetryAt().isAfter(now)) {
            return claim(connection, null, true, false);
        }
        if (connection.getHistorySyncLockId() != null
                && connection.getHistorySyncLockUntil() != null
                && connection.getHistorySyncLockUntil().isAfter(now)) {
            return claim(connection, null, true);
        }
        if (connection.getBackfillLockId() != null
                && connection.getBackfillLockUntil() != null
                && connection.getBackfillLockUntil().isAfter(now)) {
            return claim(connection, null, true);
        }

        connection.setGmailSyncRetryAt(null);
        UUID lockId = UUID.randomUUID();
        connection.setHistorySyncLockId(lockId);
        connection.setHistorySyncLockUntil(now.plus(LOCK_DURATION));
        connections.save(connection);
        return claim(connection, lockId, false);
    }

    @Transactional
    public void defer(Long connectionId, UUID lockId, Instant retryAt, int maxAttempts) {
        GmailConnection connection = requireLock(connectionId, lockId);
        int attempts = connection.getGmailSyncRetryAttempts() + 1;
        connection.setGmailSyncRetryAttempts(attempts);
        connection.setGmailSyncRetryAt(retryAt);
        connection.setGmailSyncRetryExhausted(attempts >= maxAttempts);
        releaseLock(connection);
        connections.save(connection);
    }

    @Transactional
    public void allowRetry(Long userId) {
        GmailConnection connection = connections.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new GmailBackfillException(
                        HttpStatus.CONFLICT, "GMAIL_NOT_CONNECTED", "Nincs csatlakoztatott Gmail-fiók."));
        connection.setGmailSyncRetryAttempts(0);
        connection.setGmailSyncRetryAt(null);
        connection.setGmailSyncRetryExhausted(false);
        connections.save(connection);
    }

    @Transactional
    public void markAuthenticationRequired(Long connectionId, UUID lockId) {
        GmailConnection connection = requireLock(connectionId, lockId);
        connection.setGmailSyncAuthRequired(true);
        releaseLock(connection);
        connections.save(connection);
    }

    @Transactional
    public void beginScan(
            Long connectionId,
            UUID lockId,
            String baselineId,
            Instant cutoff,
            String query,
            boolean recovery) {
        GmailConnection connection = requireLock(connectionId, lockId);
        connection.setHistorySyncPhase(recovery ? "RECOVERY_SCAN" : "BOOTSTRAP");
        connection.setHistorySyncBaselineId(baselineId);
        connection.setHistorySyncCutoff(cutoff);
        connection.setHistorySyncPolicyMonths(syncWindow.configuredMonths());
        connection.setHistorySyncQuery(query);
        connection.setHistorySyncPageToken(null);
        clearPending(connection);
        connections.save(connection);
    }

    @Transactional
    public void renew(Long connectionId, UUID lockId) {
        GmailConnection connection = requireLock(connectionId, lockId);
        connection.setHistorySyncLockUntil(clock.instant().plus(LOCK_DURATION));
        connections.save(connection);
    }

    @Transactional
    public int completeScanPage(
            Long connectionId,
            UUID lockId,
            List<Email> importedEmails,
            String nextPageToken) {
        GmailConnection connection = requireLock(connectionId, lockId);
        int importedCount = persistEmails(connectionId, importedEmails);
        connection.setHistorySyncPageToken(nextPageToken);
        if (nextPageToken == null || nextPageToken.isBlank()) {
            connection.setHistorySyncPhase("HISTORY");
            connection.setHistorySyncPageToken(null);
        }
        connection.setGmailSyncRetryAttempts(0);
        connection.setGmailSyncRetryAt(null);
        connection.setGmailSyncRetryExhausted(false);
        releaseLock(connection);
        connections.save(connection);
        return importedCount;
    }

    @Transactional
    public int persistPartialEmails(Long connectionId, UUID lockId, List<Email> importedEmails) {
        GmailConnection connection = requireLock(connectionId, lockId);
        int importedCount = persistEmails(connectionId, importedEmails);
        connections.save(connection);
        return importedCount;
    }

    @Transactional
    public void beginHistoryCycle(Long connectionId, UUID lockId, Instant cutoff, String query) {
        GmailConnection connection = requireLock(connectionId, lockId);
        connection.setHistorySyncPhase("HISTORY");
        connection.setHistorySyncCutoff(cutoff);
        connection.setHistorySyncPolicyMonths(syncWindow.configuredMonths());
        connection.setHistorySyncQuery(query);
        connection.setHistorySyncBaselineId(null);
        connection.setHistorySyncPageToken(null);
        clearPending(connection);
        connections.save(connection);
    }

    @Transactional
    public void stageHistoryPage(
            Long connectionId,
            UUID lockId,
            List<String> messageIds,
            String nextPageToken,
            String responseHistoryId) {
        GmailConnection connection = requireLock(connectionId, lockId);
        connection.setHistorySyncMessageIds(String.join("\n", messageIds));
        connection.setHistorySyncMessageOffset(0);
        connection.setHistorySyncPendingPageToken(nextPageToken);
        connection.setHistorySyncPendingHistoryId(responseHistoryId);
        connections.save(connection);
    }

    @Transactional
    public void resetHistoryPage(Long connectionId, UUID lockId) {
        GmailConnection connection = requireLock(connectionId, lockId);
        connection.setHistorySyncPageToken(null);
        clearPending(connection);
        connections.save(connection);
    }

    @Transactional
    public int completeHistoryBatch(
            Long connectionId,
            UUID lockId,
            List<Email> importedEmails,
            int processedCount) {
        GmailConnection connection = requireLock(connectionId, lockId);
        int importedCount = persistEmails(connectionId, importedEmails);
        List<String> ids = pendingMessageIds(connection);
        int nextOffset = connection.getHistorySyncMessageOffset() + processedCount;
        if (nextOffset > ids.size()) {
            throw new GmailBackfillException(
                    HttpStatus.CONFLICT, "HISTORY_CHECKPOINT_CHANGED",
                    "A Gmail-szinkronizáció állapota megváltozott; próbáld újra.");
        }
        connection.setHistorySyncMessageOffset(nextOffset);

        if (nextOffset == ids.size()) {
            String nextPageToken = connection.getHistorySyncPendingPageToken();
            if (nextPageToken != null && !nextPageToken.isBlank()) {
                connection.setHistorySyncPageToken(nextPageToken);
            } else {
                connection.setHistorySyncCursor(connection.getHistorySyncPendingHistoryId());
                connection.setHistorySyncPageToken(null);
                connection.setHistorySyncPhase(null);
                connection.setHistorySyncBaselineId(null);
                connection.setGmailSyncRetryAttempts(0);
                connection.setGmailSyncRetryAt(null);
                connection.setGmailSyncRetryExhausted(false);
            }
            clearPending(connection);
        }
        connection.setGmailSyncRetryAttempts(0);
        connection.setGmailSyncRetryAt(null);
        connection.setGmailSyncRetryExhausted(false);
        releaseLock(connection);
        connections.save(connection);
        return importedCount;
    }

    @Transactional
    public void release(Long connectionId, UUID lockId) {
        GmailConnection connection = connections.findByIdForUpdate(connectionId).orElse(null);
        if (connection == null || !lockId.equals(connection.getHistorySyncLockId())) {
            return;
        }
        releaseLock(connection);
        connections.save(connection);
    }

    private int persistEmails(Long connectionId, List<Email> importedEmails) {
        int importedCount = 0;
        for (Email email : importedEmails) {
            importedCount += emails.insertIfAbsent(
                    connectionId,
                    email.getGmailMessageId(),
                    email.getSubject(),
                    email.getFromAddress(),
                    email.getBodyText(),
                    email.getEmailReceivedAt(),
                    email.getCategory().name());
        }
        return importedCount;
    }

    private GmailConnection requireLock(Long connectionId, UUID lockId) {
        GmailConnection connection = connections.findByIdForUpdate(connectionId)
                .orElseThrow(() -> new GmailBackfillException(
                        HttpStatus.CONFLICT, "HISTORY_STATE_CHANGED", "A Gmail-kapcsolat állapota megváltozott."));
        if (lockId == null || !lockId.equals(connection.getHistorySyncLockId())) {
            throw new GmailBackfillException(
                    HttpStatus.CONFLICT, "HISTORY_LOCK_EXPIRED", "A Gmail-szinkronizáció zárolása lejárt.");
        }
        return connection;
    }

    private Claim claim(GmailConnection connection, UUID lockId, boolean inProgress) {
        return claim(connection, lockId, inProgress, false, false);
    }

    private Claim claim(GmailConnection connection, UUID lockId, boolean inProgress, boolean retryExhausted) {
        return claim(connection, lockId, inProgress, retryExhausted, false);
    }

    private Claim claim(
            GmailConnection connection,
            UUID lockId,
            boolean inProgress,
            boolean retryExhausted,
            boolean authenticationRequired) {
        return new Claim(
                inProgress ? Status.IN_PROGRESS : Status.CLAIMED,
                lockId,
                connection.getId(),
                connection.getGmailAddress(),
                connection.getHistorySyncCursor(),
                connection.getHistorySyncPhase(),
                connection.getHistorySyncBaselineId(),
                connection.getHistorySyncCutoff(),
                connection.getHistorySyncPolicyMonths(),
                connection.getHistorySyncQuery(),
                connection.getHistorySyncPageToken(),
                connection.getHistorySyncPendingPageToken(),
                connection.getHistorySyncPendingHistoryId(),
                pendingMessageIds(connection),
                connection.getHistorySyncMessageOffset(),
                connection.getGmailSyncRetryAt(),
                connection.getGmailSyncRetryAttempts(),
                retryExhausted,
                authenticationRequired);
    }

    private List<String> pendingMessageIds(GmailConnection connection) {
        String serialized = connection.getHistorySyncMessageIds();
        if (serialized == null || serialized.isBlank()) {
            return List.of();
        }
        return serialized.lines().toList();
    }

    private void clearPending(GmailConnection connection) {
        connection.setHistorySyncMessageIds(null);
        connection.setHistorySyncMessageOffset(0);
        connection.setHistorySyncPendingPageToken(null);
        connection.setHistorySyncPendingHistoryId(null);
    }

    private void releaseLock(GmailConnection connection) {
        connection.setHistorySyncLockId(null);
        connection.setHistorySyncLockUntil(null);
    }

    public enum Status {
        CLAIMED,
        IN_PROGRESS,
        RETRY_EXHAUSTED
    }

    public record Claim(
            Status status,
            UUID lockId,
            Long connectionId,
            String gmailAddress,
            String cursor,
            String phase,
            String baselineId,
            Instant cutoff,
            Integer policyMonths,
            String query,
            String pageToken,
            String pendingPageToken,
            String pendingHistoryId,
            List<String> pendingMessageIds,
            int messageOffset,
            Instant retryAt,
            int retryAttempts,
            boolean retryExhausted,
            boolean authenticationRequired) {
        public Claim(
                Status status,
                UUID lockId,
                Long connectionId,
                String gmailAddress,
                String cursor,
                String phase,
                String baselineId,
                Instant cutoff,
                Integer policyMonths,
                String query,
                String pageToken,
                String pendingPageToken,
                String pendingHistoryId,
                List<String> pendingMessageIds,
                int messageOffset) {
            this(status, lockId, connectionId, gmailAddress, cursor, phase, baselineId, cutoff,
                    policyMonths, query, pageToken, pendingPageToken, pendingHistoryId,
                    pendingMessageIds, messageOffset, null, 0, false, false);
        }
    }
}
