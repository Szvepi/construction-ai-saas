package com.buildassist.service;

import com.buildassist.repository.GmailConnectionRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailSyncSchedulerTest {

    @Test
    void resumesEveryConnectedAccountWithoutBrowserRequests() {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        GmailInboxSyncService syncService = mock(GmailInboxSyncService.class);
        when(connections.findAllUserIdsForGmailSync()).thenReturn(List.of(3L, 7L, 11L));
        GmailSyncScheduler scheduler = new GmailSyncScheduler(
                connections, syncService, new SyncTaskExecutor());

        scheduler.resumeConnectedAccounts();

        verify(syncService).resumeForScheduler(3L);
        verify(syncService).resumeForScheduler(7L);
        verify(syncService).resumeForScheduler(11L);
    }

    @Test
    void doesNotQueueOldBackfillWorkAheadOfNewlyEligibleAccounts() {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        GmailInboxSyncService syncService = mock(GmailInboxSyncService.class);
        when(connections.findAllUserIdsForGmailSync()).thenReturn(
                java.util.stream.LongStream.rangeClosed(1, 12).boxed().toList());
        List<Runnable> queuedTasks = new ArrayList<>();
        TaskExecutor executor = queuedTasks::add;
        GmailSyncScheduler scheduler = new GmailSyncScheduler(connections, syncService, executor);

        scheduler.resumeConnectedAccounts();

        org.junit.jupiter.api.Assertions.assertEquals(4, queuedTasks.size());
        queuedTasks.forEach(Runnable::run);
        scheduler.resumeConnectedAccounts();
        org.junit.jupiter.api.Assertions.assertEquals(8, queuedTasks.size());
    }
}
