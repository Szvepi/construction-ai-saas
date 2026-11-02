package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailRetryPolicyTest {

    @Test
    void exponentialBackoffGrowsAndHonorsServerRetryAfter() {
        AppProperties properties = new AppProperties();
        GmailRetryPolicy policy = new GmailRetryPolicy(properties);
        Instant now = Instant.parse("2026-10-22T18:00:00Z");

        Instant first = policy.retryAt(1, null, now);
        Instant second = policy.retryAt(2, null, now);
        Instant capped = policy.retryAt(20, null, now);
        Instant exhaustedCooldown = policy.retryAt(policy.maxAttempts() + 1, null, now);
        Instant serverRetry = now.plusSeconds(120);
        Instant afterServerRetry = policy.retryAt(2, serverRetry, now);

        assertTrue(first.isAfter(now.plusSeconds(4)) && first.isBefore(now.plusSeconds(7)));
        assertTrue(second.isAfter(first));
        assertTrue(!capped.isAfter(now.plusSeconds(policy.maxDelaySeconds())));
        assertTrue(exhaustedCooldown.equals(now.plusSeconds(900)));
        assertTrue(!afterServerRetry.isBefore(serverRetry));
    }
}
