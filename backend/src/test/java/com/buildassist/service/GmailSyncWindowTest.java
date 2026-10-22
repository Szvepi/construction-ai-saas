package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailSyncWindowTest {

    @Test
    void calculatesSixCalendarMonthsInUtcAndIncludesExactBoundary() {
        Instant now = Instant.parse("2026-10-22T18:00:00Z");
        GmailSyncWindow window = new GmailSyncWindow(new AppProperties(), Clock.fixed(now, ZoneOffset.UTC));
        Instant cutoff = Instant.parse("2026-04-22T18:00:00Z");

        assertEquals(cutoff, window.currentCutoff());
        assertFalse(window.isWithinCurrentAndPinnedWindow(cutoff.minusMillis(1), cutoff));
        assertTrue(window.isWithinCurrentAndPinnedWindow(cutoff, cutoff));
        assertTrue(window.isWithinCurrentAndPinnedWindow(cutoff.plusMillis(1), cutoff));
        assertTrue(window.queryFor(cutoff).contains("after:" + (cutoff.getEpochSecond() - 1)));
    }

    @Test
    void rejectsMessageWhenRollingCurrentCutoffMovesPastPinnedCutoff() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-22T18:00:00Z"));
        GmailSyncWindow window = new GmailSyncWindow(new AppProperties(), clock);
        Instant pinnedCutoff = window.currentCutoff();
        Instant betweenCutoffs = pinnedCutoff.plusSeconds(60);

        clock.set(Instant.parse("2026-10-23T18:00:00Z"));

        assertFalse(window.isWithinCurrentAndPinnedWindow(betweenCutoffs, pinnedCutoff));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
