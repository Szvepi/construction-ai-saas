package com.buildassist.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailApiExceptionTest {

    @Test
    void distinguishesKnownGmailQuotaReasons() {
        assertRateLimit(403, "rateLimitExceeded", "RATE_LIMIT");
        assertRateLimit(403, "userRateLimitExceeded", "USER_QUOTA");
        assertRateLimit(403, "quotaExceeded", "PROJECT_QUOTA");
        assertRateLimit(429, "tooManyConcurrentRequests", "CONCURRENT_REQUESTS");
    }

    @Test
    void leavesUnknown429AndPermissionFailuresDistinct() {
        GmailApiException unknown429 = new GmailApiException(429, "UNKNOWN", GmailApiOperation.MESSAGES_LIST, null);
        assertTrue(unknown429.isTransientRateLimit());
        assertEquals("HTTP_429_UNCLASSIFIED", unknown429.rateLimitClass());

        GmailApiException permissionDenied =
                new GmailApiException(403, "insufficientPermissions", GmailApiOperation.MESSAGES_LIST, null);
        assertFalse(permissionDenied.isTransientRateLimit());
        assertEquals("NOT_A_RATE_LIMIT", permissionDenied.rateLimitClass());
    }

    private void assertRateLimit(int status, String reason, String expectedClass) {
        GmailApiException exception = new GmailApiException(status, reason, GmailApiOperation.MESSAGES_LIST, null);
        assertTrue(exception.isTransientRateLimit());
        assertEquals(expectedClass, exception.rateLimitClass());
    }
}
