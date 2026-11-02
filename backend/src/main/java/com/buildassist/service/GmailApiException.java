package com.buildassist.service;

import java.time.Instant;

public class GmailApiException extends RuntimeException {

    private final int status;
    private final String reason;
    private final GmailApiOperation operation;
    private final Instant retryAt;

    public GmailApiException(int status, String reason, GmailApiOperation operation, Instant retryAt) {
        super("Gmail API request failed");
        this.status = status;
        this.reason = reason;
        this.operation = operation;
        this.retryAt = retryAt;
    }

    public int getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public GmailApiOperation getOperation() {
        return operation;
    }

    public Instant getRetryAt() {
        return retryAt;
    }

    public boolean isTransientRateLimit() {
        return status == 429
                || (status == 403 && (reason.equals("rateLimitExceeded")
                        || reason.equals("userRateLimitExceeded")
                        || reason.equals("quotaExceeded")
                        || reason.equals("dailyLimitExceeded")
                        || reason.equals("projectRateLimitExceeded")
                        || reason.equals("tooManyConcurrentRequests")));
    }

    public boolean isTransientServerError() {
        return status >= 500;
    }

    public String rateLimitClass() {
        if (reason.equals("userRateLimitExceeded")) {
            return "USER_QUOTA";
        }
        if (reason.equals("quotaExceeded")
                || reason.equals("dailyLimitExceeded")
                || reason.equals("projectRateLimitExceeded")) {
            return "PROJECT_QUOTA";
        }
        if (reason.equals("tooManyConcurrentRequests")) {
            return "CONCURRENT_REQUESTS";
        }
        if (reason.equals("rateLimitExceeded")) {
            return "RATE_LIMIT";
        }
        return status == 429 ? "HTTP_429_UNCLASSIFIED" : "NOT_A_RATE_LIMIT";
    }
}
