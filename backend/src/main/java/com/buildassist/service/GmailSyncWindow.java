package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class GmailSyncWindow {

    private final AppProperties.Gmail gmailProperties;
    private final Clock clock;

    public GmailSyncWindow(AppProperties appProperties, Clock clock) {
        this.gmailProperties = appProperties.getGmail();
        this.clock = clock;
        if (gmailProperties.getSyncWindowMonths() < 1) {
            throw new IllegalArgumentException("app.gmail.sync-window-months must be positive");
        }
    }

    public Instant currentCutoff() {
        return clock.instant()
                .atZone(ZoneOffset.UTC)
                .minusMonths(gmailProperties.getSyncWindowMonths())
                .toInstant();
    }

    public String queryFor(Instant cutoff) {
        long cutoffEpochSeconds = Math.max(0, cutoff.getEpochSecond() - 1);
        return "label:INBOX category:primary -category:promotions -category:social "
                + "-category:updates -in:spam -in:trash after:" + cutoffEpochSeconds;
    }

    public boolean isWithinCurrentAndPinnedWindow(Instant receivedAt, Instant pinnedCutoff) {
        return receivedAt != null
                && !receivedAt.isBefore(pinnedCutoff)
                && !receivedAt.isBefore(currentCutoff());
    }

    public int configuredMonths() {
        return gmailProperties.getSyncWindowMonths();
    }
}
