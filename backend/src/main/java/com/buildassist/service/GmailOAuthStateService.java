package com.buildassist.service;

import com.buildassist.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class GmailOAuthStateService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] signingKey;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public GmailOAuthStateService(AppProperties properties, Clock clock) {
        String secret = properties.getJwt().getSecret();
        this.signingKey = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String issue(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Invalid Gmail OAuth user");
        }
        byte[] nonce = new byte[24];
        secureRandom.nextBytes(nonce);
        String payload = "v1." + userId + "." + clock.instant().getEpochSecond() + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public Long verify(String state) {
        if (state == null || state.length() > 1024) {
            throw new IllegalArgumentException("Invalid Gmail OAuth state");
        }
        String[] parts = state.split("\\.", -1);
        if (parts.length != 2 || !MessageDigest.isEqual(
                sign(parts[0]).getBytes(StandardCharsets.US_ASCII),
                parts[1].getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Invalid Gmail OAuth state");
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\.", -1);
            if (fields.length != 4 || !"v1".equals(fields[0]) || fields[3].isBlank()) {
                throw new IllegalArgumentException("Invalid Gmail OAuth state");
            }
            long userId = Long.parseLong(fields[1]);
            Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(fields[2]));
            Instant now = clock.instant();
            if (userId <= 0 || issuedAt.isAfter(now.plusSeconds(60))
                    || issuedAt.plus(STATE_TTL).isBefore(now)) {
                throw new IllegalArgumentException("Expired Gmail OAuth state");
            }
            return userId;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Gmail OAuth state");
        }
    }

    private String sign(String value) {
        try {
            if (signingKey.length < 32) {
                throw new IllegalStateException("JWT_SECRET must be at least 32 UTF-8 bytes for Gmail OAuth state");
            }
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign Gmail OAuth state", ex);
        }
    }
}
