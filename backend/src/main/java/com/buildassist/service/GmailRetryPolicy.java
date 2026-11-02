package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class GmailRetryPolicy {

    private final AppProperties.Gmail properties;

    public GmailRetryPolicy(AppProperties appProperties) {
        this.properties = appProperties.getGmail();
    }

    public Instant retryAt(int attempt, Instant retryAfter, Instant now) {
        int exponent = Math.max(0, Math.min(attempt - 1, 30));
        long base = Math.max(1, properties.getRetryBaseDelaySeconds());
        long cap = Math.max(base, properties.getRetryMaxDelaySeconds());
        long exponential = Math.min(cap, base * (1L << exponent));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, exponential / 5 + 1));
        long delaySeconds = Math.min(cap, exponential + jitter);
        if (retryAfter != null && retryAfter.isAfter(now)) {
            delaySeconds = Math.max(delaySeconds, Duration.between(now, retryAfter).toSeconds());
        }
        return now.plusSeconds(delaySeconds);
    }

    public int maxAttempts() {
        return properties.getRetryMaxAttempts();
    }

    public int maxDelaySeconds() {
        return properties.getRetryMaxDelaySeconds();
    }
}
