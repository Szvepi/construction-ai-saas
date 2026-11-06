package com.buildassist.service;

import com.buildassist.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenEncryptionServiceTest {

    private static final String TEST_KEY = java.util.UUID.randomUUID().toString().replace("-", "");

    @Test
    void ciphertextRemainsReadableAfterServiceRestartWithSameConfiguredKey() {
        TokenEncryptionService first = new TokenEncryptionService(properties(TEST_KEY));
        String ciphertext = first.encrypt("persisted-refresh-token");

        TokenEncryptionService restarted = new TokenEncryptionService(properties(TEST_KEY));

        assertEquals("persisted-refresh-token", restarted.decrypt(ciphertext));
    }

    @Test
    void missingOrInvalidKeyFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new TokenEncryptionService(properties(null)));
        assertThrows(IllegalStateException.class, () -> new TokenEncryptionService(properties("short")));
    }

    @Test
    void ciphertextCannotBeReadWithAnotherConfiguredKey() {
        String ciphertext = new TokenEncryptionService(properties(TEST_KEY)).encrypt("stored-access-token");
        String otherKey = java.util.UUID.randomUUID().toString().replace("-", "");

        assertThrows(RuntimeException.class,
                () -> new TokenEncryptionService(properties(otherKey)).decrypt(ciphertext));
    }

    private AppProperties properties(String key) {
        AppProperties properties = new AppProperties();
        properties.getEncryption().setAesKey(key);
        return properties;
    }
}
