package com.buildassist.service;

import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.DraftQuoteSnapshotValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class DraftQuoteSnapshotCodec {

    private final ObjectMapper objectMapper;
    private final DraftQuoteSnapshotValidator validator;

    public DraftQuoteSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.validator = new DraftQuoteSnapshotValidator();
    }

    public String encode(DraftQuoteSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.schemaVersion() != DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported quote snapshot version.");
        }
        if (!Objects.equals(snapshot.fingerprint(), fingerprint(snapshot))) {
            throw new IllegalArgumentException("The quote snapshot fingerprint does not match its contents.");
        }
        List<String> validationErrors = validator.validate(snapshot);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", validationErrors));
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize the quote snapshot.", exception);
        }
    }

    public DraftQuoteSnapshot decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            DraftQuoteSnapshot snapshot = objectMapper.readValue(json, DraftQuoteSnapshot.class);
            if (snapshot == null) {
                throw new IllegalStateException("The stored quote snapshot is empty.");
            }
            if (snapshot.schemaVersion() != DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported quote snapshot version.");
            }
            if (!Objects.equals(snapshot.fingerprint(), fingerprint(snapshot))) {
                throw new IllegalStateException("The stored quote snapshot fingerprint does not match its contents.");
            }
            List<String> validationErrors = validator.validate(snapshot);
            if (!validationErrors.isEmpty()) {
                throw new IllegalStateException(String.join(" ", validationErrors));
            }
            return snapshot;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The stored quote snapshot is invalid.", exception);
        }
    }

    public DraftQuoteSnapshot seal(DraftQuoteSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        DraftQuoteSnapshot unhashed = new DraftQuoteSnapshot(
                snapshot.schemaVersion(), null, snapshot.workItems(), snapshot.lineItems());
        return new DraftQuoteSnapshot(
                snapshot.schemaVersion(), fingerprint(unhashed), snapshot.workItems(), snapshot.lineItems());
    }

    private String fingerprint(DraftQuoteSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(new DraftQuoteSnapshot(
                    snapshot.schemaVersion(), null, snapshot.workItems(), snapshot.lineItems()));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not fingerprint the quote snapshot.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
