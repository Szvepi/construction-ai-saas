package com.buildassist.service;

import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.buildassist.repository.UserRepository;
import com.buildassist.config.AppConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        GmailBackfillStateStore.class,
        GmailHistoryStateStore.class,
        GmailSyncWindow.class,
        AppConfig.class
})
class GmailBackfillStateStoreIntegrationTest {

    @Autowired
    private GmailBackfillStateStore stateStore;
    @Autowired
    private GmailHistoryStateStore historyStateStore;
    @Autowired
    private GmailConnectionRepository gmailConnectionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailRepository emailRepository;

    @Test
    void savesContinuationOnlyWithSuccessfulBatchAndSkipsExistingMessages() {
        GmailConnection connection = saveConnection("backfill-owner@example.com", "backfill@gmail.com");
        GmailBackfillStateStore.Claim first = stateStore.claim(connection.getUser().getId());
        assertEquals(GmailBackfillStateStore.ClaimStatus.STARTED, first.status());
        assertTrue(first.query().contains("label:INBOX category:primary"));
        assertTrue(first.query().contains("after:"));
        assertTrue(first.query().contains("before:"));
        org.junit.jupiter.api.Assertions.assertNotNull(first.cutoff());
        assertNull(first.pageToken());

        Email staged = sampleEmail("message-1");
        assertEquals(1, stateStore.completePage(connection.getId(), first.lockId(), List.of(staged), "page-2"));

        GmailBackfillStateStore.Claim second = stateStore.claim(connection.getUser().getId());
        assertEquals("page-2", second.pageToken());
        assertEquals(first.query(), second.query());
        assertEquals(0, stateStore.completePage(connection.getId(), second.lockId(), List.of(staged), null));

        GmailBackfillStateStore.Claim finished = stateStore.claim(connection.getUser().getId());
        assertEquals(GmailBackfillStateStore.ClaimStatus.COMPLETE, finished.status());
        assertEquals(1, emailRepository.findByGmailConnectionId(connection.getId()).size());
    }

    @Test
    void activeLeaseRejectsParallelClaimAndConnectionsAreIsolatedByUser() {
        GmailConnection firstConnection = saveConnection("lease-owner@example.com", "lease@gmail.com");
        GmailConnection secondConnection = saveConnection("lease-other@example.com", "other@gmail.com");

        GmailBackfillStateStore.Claim active = stateStore.claim(firstConnection.getUser().getId());
        GmailBackfillStateStore.Claim parallel = stateStore.claim(firstConnection.getUser().getId());
        GmailBackfillStateStore.Claim otherUser = stateStore.claim(secondConnection.getUser().getId());

        assertEquals(GmailBackfillStateStore.ClaimStatus.IN_PROGRESS, parallel.status());
        assertEquals(GmailBackfillStateStore.ClaimStatus.STARTED, otherUser.status());
        assertThrows(GmailBackfillException.class, () -> stateStore.claim(Long.MAX_VALUE));
        stateStore.release(firstConnection.getId(), active.lockId());
        stateStore.release(secondConnection.getId(), otherUser.lockId());
    }

    @Test
    void invalidatesLegacyUnboundedBackfillTokenAndKeepsStoredEmails() {
        GmailConnection connection = saveConnection("legacy-backfill@example.com", "legacy-backfill@gmail.com");
        connection.setBackfillPageToken("legacy-unbounded-token");
        connection.setBackfillQuery("label:INBOX before:1792680000");
        gmailConnectionRepository.saveAndFlush(connection);
        Email oldEmail = sampleEmail("old-stored-message");
        oldEmail.setEmailReceivedAt(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        oldEmail.setSyncedAt(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        oldEmail.setGmailConnection(connection);
        emailRepository.saveAndFlush(oldEmail);

        GmailBackfillStateStore.Claim claim = stateStore.claim(connection.getUser().getId());

        assertNull(claim.pageToken());
        assertTrue(claim.query().contains("after:"));
        assertTrue(claim.query().contains("before:"));
        assertEquals(1, emailRepository.findByGmailConnectionId(connection.getId()).size());
        stateStore.release(connection.getId(), claim.lockId());
    }

    @Test
    void historyCursorAdvancesOnlyAfterAllPendingIdsAreCommitted() {
        GmailConnection connection = saveConnection("history-owner@example.com", "history-owner@gmail.com");
        Instant cutoff = Instant.parse("2026-04-22T18:00:00Z");
        GmailHistoryStateStore.Claim claim = historyStateStore.claim(connection.getUser().getId());
        historyStateStore.beginScan(connection.getId(), claim.lockId(), "h-baseline", cutoff, "query", false);
        historyStateStore.completeScanPage(connection.getId(), claim.lockId(), List.of(), null);

        GmailHistoryStateStore.Claim firstBatchClaim = historyStateStore.claim(connection.getUser().getId());
        List<String> ids = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "history-" + i).toList();
        historyStateStore.stageHistoryPage(
                connection.getId(), firstBatchClaim.lockId(), ids, null, "h-final");
        historyStateStore.completeHistoryBatch(
                connection.getId(), firstBatchClaim.lockId(), List.of(), 50);

        GmailHistoryStateStore.Claim finalBatchClaim = historyStateStore.claim(connection.getUser().getId());
        assertNull(finalBatchClaim.cursor());
        assertEquals("h-baseline", finalBatchClaim.baselineId());
        assertEquals(50, finalBatchClaim.messageOffset());
        assertEquals(51, finalBatchClaim.pendingMessageIds().size());

        historyStateStore.completeHistoryBatch(
                connection.getId(), finalBatchClaim.lockId(), List.of(), 1);
        GmailHistoryStateStore.Claim complete = historyStateStore.claim(connection.getUser().getId());

        assertEquals("h-final", complete.cursor());
        assertNull(complete.phase());
        assertNull(complete.baselineId());
        assertTrue(complete.pendingMessageIds().isEmpty());
        historyStateStore.release(connection.getId(), complete.lockId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTransactionsCannotClaimSameConnectionTwice() throws Exception {
        String uniqueSuffix = java.util.UUID.randomUUID().toString();
        GmailConnection connection = saveConnection(
                "parallel-" + uniqueSuffix + "@example.com", "parallel-" + uniqueSuffix + "@gmail.com");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return stateStore.claim(connection.getUser().getId());
            });
            var second = executor.submit(() -> {
                start.await();
                return stateStore.claim(connection.getUser().getId());
            });
            start.countDown();
            GmailBackfillStateStore.Claim firstClaim = first.get(10, TimeUnit.SECONDS);
            GmailBackfillStateStore.Claim secondClaim = second.get(10, TimeUnit.SECONDS);

            assertEquals(1, List.of(firstClaim, secondClaim).stream()
                    .filter(claim -> claim.status() == GmailBackfillStateStore.ClaimStatus.STARTED)
                    .count());
            assertEquals(1, List.of(firstClaim, secondClaim).stream()
                    .filter(claim -> claim.status() == GmailBackfillStateStore.ClaimStatus.IN_PROGRESS)
                    .count());
            GmailBackfillStateStore.Claim owner = firstClaim.status() == GmailBackfillStateStore.ClaimStatus.STARTED
                    ? firstClaim : secondClaim;
            stateStore.release(connection.getId(), owner.lockId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void backfillAndHistoryRefreshCanPersistOverlappingBatchesConcurrently() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        GmailConnection connection = saveConnection(
                "cross-sync-" + suffix + "@example.com", "cross-sync-" + suffix + "@gmail.com");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var backfillFuture = executor.submit(() -> {
                start.await();
                return stateStore.claim(connection.getUser().getId());
            });
            var historyFuture = executor.submit(() -> {
                start.await();
                return historyStateStore.claim(connection.getUser().getId());
            });
            start.countDown();

            GmailBackfillStateStore.Claim backfill = backfillFuture.get(10, TimeUnit.SECONDS);
            GmailHistoryStateStore.Claim history = historyFuture.get(10, TimeUnit.SECONDS);
            assertEquals(GmailBackfillStateStore.ClaimStatus.STARTED, backfill.status());
            assertEquals(GmailHistoryStateStore.Status.CLAIMED, history.status());
            assertNotEquals(backfill.lockId(), history.lockId());

            GmailConnection claimed = gmailConnectionRepository.findById(connection.getId()).orElseThrow();
            assertEquals(backfill.lockId(), claimed.getBackfillLockId());
            assertEquals(history.lockId(), claimed.getHistorySyncLockId());

            historyStateStore.beginScan(
                    connection.getId(), history.lockId(), "h-baseline", Instant.parse("2026-04-22T00:00:00Z"),
                    "primary-query", false);
            historyStateStore.completeScanPage(connection.getId(), history.lockId(), List.of(), null);
            GmailHistoryStateStore.Claim historyBatch = historyStateStore.claim(connection.getUser().getId());
            historyStateStore.stageHistoryPage(
                    connection.getId(), historyBatch.lockId(),
                    List.of("shared-message", "history-only-message"), null, "h-committed");

            CountDownLatch persistStart = new CountDownLatch(1);
            Email sharedForBackfill = sampleEmail("shared-message");
            Email backfillOnly = sampleEmail("backfill-only-message");
            Email sharedForHistory = sampleEmail("shared-message");
            Email historyOnly = sampleEmail("history-only-message");
            var backfillPersist = executor.submit(() -> {
                persistStart.await();
                return stateStore.completePage(
                        connection.getId(), backfill.lockId(),
                        List.of(sharedForBackfill, backfillOnly), "backfill-next");
            });
            var historyPersist = executor.submit(() -> {
                persistStart.await();
                return historyStateStore.completeHistoryBatch(
                        connection.getId(), historyBatch.lockId(),
                        List.of(sharedForHistory, historyOnly), 2);
            });
            persistStart.countDown();

            int backfillInserted = backfillPersist.get(10, TimeUnit.SECONDS);
            int historyInserted = historyPersist.get(10, TimeUnit.SECONDS);
            assertEquals(3, backfillInserted + historyInserted);
        }

        List<Email> storedEmails = emailRepository.findByGmailConnectionId(connection.getId());
        assertEquals(3, storedEmails.size());
        assertEquals(3, storedEmails.stream().map(Email::getGmailMessageId).distinct().count());
        GmailConnection completed = gmailConnectionRepository.findById(connection.getId()).orElseThrow();
        assertEquals("backfill-next", completed.getBackfillPageToken());
        assertEquals("h-committed", completed.getHistorySyncCursor());
        assertNull(completed.getHistorySyncPhase());
        assertNull(completed.getBackfillLockId());
        assertNull(completed.getHistorySyncLockId());
    }

    @Test
    void failureAndRetryKeepTheOtherSyncCheckpointAndBothCursorsIndependent() {
        String suffix = java.util.UUID.randomUUID().toString();
        GmailConnection connection = saveConnection(
                "cross-sync-retry-" + suffix + "@example.com",
                "cross-sync-retry-" + suffix + "@gmail.com");

        GmailBackfillStateStore.Claim initialBackfill = stateStore.claim(connection.getUser().getId());
        assertEquals(0, stateStore.completePage(
                connection.getId(), initialBackfill.lockId(), List.of(), "backfill-page-2"));
        GmailBackfillStateStore.Claim activeBackfill = stateStore.claim(connection.getUser().getId());

        GmailHistoryStateStore.Claim initialHistory = historyStateStore.claim(connection.getUser().getId());
        historyStateStore.beginScan(
                connection.getId(), initialHistory.lockId(), "h-baseline",
                Instant.parse("2026-04-22T00:00:00Z"), "primary-query", false);
        historyStateStore.completeScanPage(connection.getId(), initialHistory.lockId(), List.of(), null);
        GmailHistoryStateStore.Claim activeHistory = historyStateStore.claim(connection.getUser().getId());
        historyStateStore.stageHistoryPage(
                connection.getId(), activeHistory.lockId(), List.of("history-retry-message"), null, "h-next");

        assertEquals("backfill-page-2", activeBackfill.pageToken());
        assertEquals("h-baseline", activeHistory.baselineId());
        assertNull(activeHistory.cursor());

        assertThrows(GmailBackfillException.class, () -> stateStore.completePage(
                connection.getId(), java.util.UUID.randomUUID(), List.of(), "must-not-commit"));
        GmailConnection afterBackfillFailure = gmailConnectionRepository.findById(connection.getId()).orElseThrow();
        assertEquals("backfill-page-2", afterBackfillFailure.getBackfillPageToken());
        assertEquals("h-baseline", afterBackfillFailure.getHistorySyncBaselineId());
        assertEquals("h-next", afterBackfillFailure.getHistorySyncPendingHistoryId());
        assertEquals(activeHistory.lockId(), afterBackfillFailure.getHistorySyncLockId());

        stateStore.release(connection.getId(), activeBackfill.lockId());
        GmailBackfillStateStore.Claim retriedBackfill = stateStore.claim(connection.getUser().getId());
        assertEquals(GmailBackfillStateStore.ClaimStatus.STARTED, retriedBackfill.status());
        assertEquals("backfill-page-2", retriedBackfill.pageToken());

        assertThrows(GmailBackfillException.class, () -> historyStateStore.completeHistoryBatch(
                connection.getId(), java.util.UUID.randomUUID(), List.of(), 1));
        GmailConnection afterHistoryFailure = gmailConnectionRepository.findById(connection.getId()).orElseThrow();
        assertEquals("backfill-page-2", afterHistoryFailure.getBackfillPageToken());
        assertEquals(retriedBackfill.lockId(), afterHistoryFailure.getBackfillLockId());
        assertNull(afterHistoryFailure.getHistorySyncCursor());
        assertEquals("h-next", afterHistoryFailure.getHistorySyncPendingHistoryId());

        historyStateStore.release(connection.getId(), activeHistory.lockId());
        GmailHistoryStateStore.Claim retriedHistory = historyStateStore.claim(connection.getUser().getId());
        assertEquals(GmailHistoryStateStore.Status.CLAIMED, retriedHistory.status());
        assertEquals(List.of("history-retry-message"), retriedHistory.pendingMessageIds());
        assertEquals("h-next", retriedHistory.pendingHistoryId());

        assertEquals(1, stateStore.completePage(
                connection.getId(), retriedBackfill.lockId(), List.of(sampleEmail("backfill-retry-message")),
                "backfill-page-3"));
        assertEquals(1, historyStateStore.completeHistoryBatch(
                connection.getId(), retriedHistory.lockId(), List.of(sampleEmail("history-retry-message")), 1));

        GmailConnection retried = gmailConnectionRepository.findById(connection.getId()).orElseThrow();
        assertEquals("backfill-page-3", retried.getBackfillPageToken());
        assertEquals("h-next", retried.getHistorySyncCursor());
        assertNull(retried.getHistorySyncPhase());
        assertEquals(2, emailRepository.findByGmailConnectionId(connection.getId()).size());
    }

    @Test
    void differentUsersCanRunBackfillAndHistoryRefreshIndependently() {
        String suffix = java.util.UUID.randomUUID().toString();
        GmailConnection backfillConnection = saveConnection(
                "backfill-user-" + suffix + "@example.com", "backfill-user-" + suffix + "@gmail.com");
        GmailConnection historyConnection = saveConnection(
                "history-user-" + suffix + "@example.com", "history-user-" + suffix + "@gmail.com");

        GmailBackfillStateStore.Claim backfill = stateStore.claim(backfillConnection.getUser().getId());
        GmailHistoryStateStore.Claim history = historyStateStore.claim(historyConnection.getUser().getId());
        historyStateStore.beginScan(
                historyConnection.getId(), history.lockId(), "other-user-baseline",
                Instant.parse("2026-04-22T00:00:00Z"), "primary-query", false);
        historyStateStore.completeScanPage(historyConnection.getId(), history.lockId(), List.of(), null);
        GmailHistoryStateStore.Claim historyBatch = historyStateStore.claim(historyConnection.getUser().getId());
        historyStateStore.stageHistoryPage(
                historyConnection.getId(), historyBatch.lockId(), List.of("only-history-user"), null, "other-user-h1");

        assertEquals(1, stateStore.completePage(
                backfillConnection.getId(), backfill.lockId(), List.of(sampleEmail("only-backfill-user")),
                "other-user-page"));
        assertEquals(1, historyStateStore.completeHistoryBatch(
                historyConnection.getId(), historyBatch.lockId(),
                List.of(sampleEmail("only-history-user")), 1));

        assertEquals(List.of("only-backfill-user"), emailRepository
                .findByGmailConnectionId(backfillConnection.getId()).stream()
                .map(Email::getGmailMessageId).toList());
        assertEquals(List.of("only-history-user"), emailRepository
                .findByGmailConnectionId(historyConnection.getId()).stream()
                .map(Email::getGmailMessageId).toList());
        GmailConnection persistedBackfill = gmailConnectionRepository.findById(backfillConnection.getId()).orElseThrow();
        GmailConnection persistedHistory = gmailConnectionRepository.findById(historyConnection.getId()).orElseThrow();
        assertEquals("other-user-page", persistedBackfill.getBackfillPageToken());
        assertNull(persistedBackfill.getHistorySyncCursor());
        assertEquals("other-user-h1", persistedHistory.getHistorySyncCursor());
        assertNull(persistedHistory.getBackfillPageToken());
    }

    private GmailConnection saveConnection(String userEmail, String gmailAddress) {
        User user = new User();
        user.setEmail(userEmail);
        user.setPasswordHash("integration-test");
        user.setActive(true);
        user = userRepository.saveAndFlush(user);

        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        connection.setGmailAddress(gmailAddress);
        connection.setAccessTokenEncrypted("encrypted-access");
        connection.setRefreshTokenEncrypted("encrypted-refresh");
        connection.setTokenExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));
        return gmailConnectionRepository.saveAndFlush(connection);
    }

    private Email sampleEmail(String messageId) {
        Email email = new Email();
        email.setGmailMessageId(messageId);
        email.setSubject("Sample request");
        email.setFromAddress("client@example.com");
        email.setBodyText("Need a quote");
        email.setEmailReceivedAt(OffsetDateTime.parse("2026-10-20T10:00:00Z"));
        email.setCategory(EmailCategory.QUOTE_REQUEST);
        return email;
    }
}
