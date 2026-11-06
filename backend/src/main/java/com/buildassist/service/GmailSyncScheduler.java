package com.buildassist.service;

import com.buildassist.repository.GmailConnectionRepository;
import java.time.Clock;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.gmail", name = "sync-enabled", havingValue = "true", matchIfMissing = true)
public class GmailSyncScheduler {

    private static final int MAX_SCHEDULED_USERS = 4;
    private static final Logger log = LoggerFactory.getLogger(GmailSyncScheduler.class);
    private final GmailConnectionRepository connections;
    private final GmailInboxSyncService syncService;
    private final TaskExecutor executor;
    private final Clock clock;
    private final Set<Long> scheduledUsers = ConcurrentHashMap.newKeySet();

    public GmailSyncScheduler(
            GmailConnectionRepository connections,
            GmailInboxSyncService syncService,
            @Qualifier("gmailSyncExecutor") TaskExecutor executor,
            Clock clock) {
        this.connections = connections;
        this.syncService = syncService;
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.gmail.sync-poll-interval-ms:30000}")
    public void resumeConnectedAccounts() {
        List<Long> userIds = new java.util.ArrayList<>(connections.findUserIdsDueForGmailSync(clock.instant()));
        Collections.shuffle(userIds);
        for (Long userId : userIds) {
            if (scheduledUsers.size() >= MAX_SCHEDULED_USERS) {
                break;
            }
            if (!scheduledUsers.add(userId)) {
                continue;
            }
            try {
                executor.execute(() -> {
                    try {
                        syncService.resumeForScheduler(userId);
                    } catch (RuntimeException ex) {
                        log.warn("SYNC_FAILED event=SYNC_FAILED trigger=SCHEDULER exceptionCategory={}",
                                exceptionCategory(ex));
                    } finally {
                        scheduledUsers.remove(userId);
                    }
                });
            } catch (RuntimeException ex) {
                scheduledUsers.remove(userId);
                log.warn("SYNC_FAILED event=SYNC_FAILED trigger=SCHEDULER exceptionCategory={} stage=SUBMIT",
                        exceptionCategory(ex));
            }
        }
    }

    private String exceptionCategory(RuntimeException exception) {
        if (exception instanceof GmailBackfillException syncException) {
            return syncException.getCode();
        }
        if (exception instanceof GmailApiException apiException) {
            return apiException.getReason();
        }
        return exception.getClass().getSimpleName();
    }
}
