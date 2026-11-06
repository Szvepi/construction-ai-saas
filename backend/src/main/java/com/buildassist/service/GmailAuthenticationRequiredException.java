package com.buildassist.service;

import org.springframework.http.HttpStatus;

public class GmailAuthenticationRequiredException extends GmailBackfillException {

    private final String reason;

    public GmailAuthenticationRequiredException(String reason, String message) {
        super(HttpStatus.FAILED_DEPENDENCY, "GMAIL_AUTHENTICATION_REQUIRED", message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
