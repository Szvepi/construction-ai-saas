package com.buildassist.service;

import java.time.Instant;

public class GmailRateLimitDeferredException extends RuntimeException {

    private final Instant retryAt;
    private final String reason;

    public GmailRateLimitDeferredException(Instant retryAt, String reason) {
        super("Gmail request deferred by rate limits");
        this.retryAt = retryAt;
        this.reason = reason;
    }

    public Instant getRetryAt() {
        return retryAt;
    }

    public String getReason() {
        return reason;
    }
}
