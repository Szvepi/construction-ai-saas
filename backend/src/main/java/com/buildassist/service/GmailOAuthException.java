package com.buildassist.service;

public class GmailOAuthException extends RuntimeException {

    private final String category;

    public GmailOAuthException(String category) {
        super(category);
        this.category = category;
    }

    public GmailOAuthException(String category, Throwable cause) {
        super(category, cause);
        this.category = category;
    }

    public String getCategory() {
        return category;
    }
}
