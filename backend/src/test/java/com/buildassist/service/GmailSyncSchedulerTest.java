package com.buildassist.service;

import com.buildassist.repository.GmailConnectionRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

class GmailSyncSchedulerTest {

    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-22T18:00:00Z");
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reconnectRequiredConnectionAcrossRepeatedPollsIsNotScheduledOrLogged(
            CapturedOutput output) {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        GmailInboxSyncService syncService = mock(GmailInboxSyncService.class);
        when(connections.findUserIdsDueForGmailSync(NOW)).thenReturn(List.of());
        GmailSyncScheduler scheduler = new GmailSyncScheduler(
                connections, syncService, new SyncTaskExecutor(), CLOCK);

        for (int i = 0; i < 5; i++) {
            scheduler.resumeConnectedAccounts();
        }

        verify(syncService, never()).resumeForScheduler(anyLong());
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains("WARN"));
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains("ERROR"));
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains("SYNC_FAILED"));
    }

    @Test
    void resumesEveryConnectedAccountWithoutBrowserRequests() {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        GmailInboxSyncService syncService = mock(GmailInboxSyncService.class);
        when(connections.findUserIdsDueForGmailSync(NOW)).thenReturn(List.of(3L, 7L, 11L));
        GmailSyncScheduler scheduler = new GmailSyncScheduler(
                connections, syncService, new SyncTaskExecutor(), CLOCK);

        scheduler.resumeConnectedAccounts();

        verify(syncService).resumeForScheduler(3L);
        verify(syncService).resumeForScheduler(7L);
        verify(syncService).resumeForScheduler(11L);
    }

    @Test
    void aFailingConnectionDoesNotBlockOtherUsers() {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        GmailInboxSyncService syncService = mock(GmailInboxSyncService.class);
        when(connections.findUserIdsDueForGmailSync(NOW)).thenReturn(List.of(3L, 7L));
        when(syncService.resumeForScheduler(3L)).thenThrow(new IllegalStateException("boom"));
        GmailSyncScheduler scheduler = new GmailSyncScheduler(
                connections, syncService, new SyncTaskExecutor(), CLOCK);

        scheduler.resumeConnectedAccounts();

        verify(syncService).resumeForScheduler(7L);
    }

    @Test
    void doesNotQueueOldBackfillWorkAheadOfNewlyEligibleAccounts() {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        GmailInboxSyncService syncService = mock(GmailInboxSyncService.class);
        when(connections.findUserIdsDueForGmailSync(NOW)).thenReturn(
                java.util.stream.LongStream.rangeClosed(1, 12).boxed().toList());
        List<Runnable> queuedTasks = new ArrayList<>();
        TaskExecutor executor = queuedTasks::add;
        GmailSyncScheduler scheduler = new GmailSyncScheduler(connections, syncService, executor, CLOCK);

        scheduler.resumeConnectedAccounts();

        org.junit.jupiter.api.Assertions.assertEquals(4, queuedTasks.size());
        queuedTasks.forEach(Runnable::run);
        scheduler.resumeConnectedAccounts();
        org.junit.jupiter.api.Assertions.assertEquals(8, queuedTasks.size());
    }
}
