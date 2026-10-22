package com.buildassist.service;

import com.buildassist.model.Email;
import com.buildassist.model.GmailConnection;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GmailBackfillStateStore {

    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    private final GmailConnectionRepository gmailConnectionRepository;
    private final EmailRepository emailRepository;
    private final GmailSyncWindow syncWindow;
    private final Clock clock;

    public GmailBackfillStateStore(
            GmailConnectionRepository gmailConnectionRepository,
            EmailRepository emailRepository,
            GmailSyncWindow syncWindow,
            Clock clock) {
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.emailRepository = emailRepository;
        this.syncWindow = syncWindow;
        this.clock = clock;
    }

    @Transactional
    public Claim claim(Long userId) {
        GmailConnection connection = gmailConnectionRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new GmailBackfillException(
                        HttpStatus.CONFLICT, "GMAIL_NOT_CONNECTED", "Nincs csatlakoztatott Gmail-fiók."));
        Instant now = clock.instant();

        if (connection.isBackfillCompleted()) {
            if (connection.getBackfillPolicyMonths() == null) {
                connection.setBackfillPolicyMonths(syncWindow.configuredMonths());
                connection.setBackfillCutoff(syncWindow.currentCutoff());
                connection.setBackfillQuery(syncWindow.queryFor(connection.getBackfillCutoff())
                        + " before:" + now.getEpochSecond());
                gmailConnectionRepository.save(connection);
                return new Claim(ClaimStatus.COMPLETE, null, connection.getId(),
                        connection.getGmailAddress(), null, connection.getBackfillQuery(),
                        connection.getBackfillCutoff());
            }
            if (connection.getBackfillPolicyMonths() == syncWindow.configuredMonths()) {
                return new Claim(ClaimStatus.COMPLETE, null, connection.getId(),
                        connection.getGmailAddress(), null, connection.getBackfillQuery(),
                        connection.getBackfillCutoff());
            }
            connection.setBackfillCompleted(false);
        }
        if (connection.getBackfillLockId() != null
                && connection.getBackfillLockUntil() != null
                && connection.getBackfillLockUntil().isAfter(now)) {
            return new Claim(ClaimStatus.IN_PROGRESS, null, connection.getId(),
                    connection.getGmailAddress(), null, connection.getBackfillQuery());
        }

        if (connection.getBackfillCutoff() == null
                || connection.getBackfillPolicyMonths() == null
                || connection.getBackfillPolicyMonths() != syncWindow.configuredMonths()
                || connection.getBackfillQuery() == null) {
            Instant cutoff = syncWindow.currentCutoff();
            connection.setBackfillPageToken(null);
            connection.setBackfillCutoff(cutoff);
            connection.setBackfillPolicyMonths(syncWindow.configuredMonths());
            connection.setBackfillQuery(
                    syncWindow.queryFor(cutoff) + " before:" + now.getEpochSecond());
        }
        UUID lockId = UUID.randomUUID();
        connection.setBackfillLockId(lockId);
        connection.setBackfillLockUntil(now.plus(LOCK_DURATION));
        gmailConnectionRepository.save(connection);

        return new Claim(
                ClaimStatus.STARTED,
                lockId,
                connection.getId(),
                connection.getGmailAddress(),
                connection.getBackfillPageToken(),
                connection.getBackfillQuery(),
                connection.getBackfillCutoff());
    }

    @Transactional
    public void renew(Long connectionId, UUID lockId) {
        GmailConnection connection = requireActiveLock(connectionId, lockId);
        connection.setBackfillLockUntil(clock.instant().plus(LOCK_DURATION));
        gmailConnectionRepository.save(connection);
    }

    @Transactional
    public int completePage(
            Long connectionId,
            UUID lockId,
            List<Email> emails,
            String nextPageToken) {
        GmailConnection connection = requireActiveLock(connectionId, lockId);
        int importedCount = 0;
        for (Email email : emails) {
            importedCount += emailRepository.insertIfAbsent(
                    connectionId,
                    email.getGmailMessageId(),
                    email.getSubject(),
                    email.getFromAddress(),
                    email.getBodyText(),
                    email.getEmailReceivedAt(),
                    email.getCategory().name());
        }

        connection.setBackfillPageToken(nextPageToken);
        connection.setBackfillCompleted(nextPageToken == null || nextPageToken.isBlank());
        connection.setBackfillLockId(null);
        connection.setBackfillLockUntil(null);
        gmailConnectionRepository.save(connection);
        return importedCount;
    }

    @Transactional
    public void release(Long connectionId, UUID lockId) {
        GmailConnection connection = gmailConnectionRepository.findByIdForUpdate(connectionId).orElse(null);
        if (connection == null || !lockId.equals(connection.getBackfillLockId())) {
            return;
        }
        connection.setBackfillLockId(null);
        connection.setBackfillLockUntil(null);
        gmailConnectionRepository.save(connection);
    }

    private GmailConnection requireActiveLock(Long connectionId, UUID lockId) {
        GmailConnection connection = gmailConnectionRepository.findByIdForUpdate(connectionId)
                .orElseThrow(() -> new GmailBackfillException(
                        HttpStatus.CONFLICT, "BACKFILL_STATE_CHANGED", "A Gmail-kapcsolat állapota megváltozott."));
        if (!lockId.equals(connection.getBackfillLockId())) {
            throw new GmailBackfillException(
                    HttpStatus.CONFLICT, "BACKFILL_STATE_CHANGED", "A Gmail-betöltés zárolása lejárt.");
        }
        return connection;
    }

    public enum ClaimStatus {
        STARTED,
        IN_PROGRESS,
        COMPLETE
    }

    public record Claim(
            ClaimStatus status,
            UUID lockId,
            Long connectionId,
            String gmailAddress,
            String pageToken,
            String query,
            Instant cutoff) {
        public Claim(
                ClaimStatus status,
                UUID lockId,
                Long connectionId,
                String gmailAddress,
                String pageToken,
                String query) {
            this(status, lockId, connectionId, gmailAddress, pageToken, query, Instant.EPOCH);
        }
    }
}
