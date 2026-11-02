package com.buildassist.service;

public interface GmailRequestLimiter {

    Permit acquire(Long connectionId, GmailApiOperation operation);

    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
