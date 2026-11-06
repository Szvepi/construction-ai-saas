package com.buildassist.service;

import com.buildassist.model.GmailConnection;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves Gmail access tokens for background sync from the persisted, encrypted connection
 * credentials. It does not depend on any browser session or SecurityContext.
 */
@Service
public class GmailTokenProvider {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);
    private static final Set<String> APPLICATION_CONFIGURATION_ERRORS =
            Set.of("invalid_client", "unauthorized_client", "invalid_request");
    private static final String RECONNECT_MESSAGE =
            "A Gmail-kapcsolat lejárt vagy megszakadt. Csatlakoztasd újra a levelezést a szinkronizáció folytatásához.";

    private final GmailConnectionRepository connections;
    private final TokenEncryptionService encryption;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String clientId;
    private final String clientSecret;

    public GmailTokenProvider(
            GmailConnectionRepository connections,
            TokenEncryptionService encryption,
            @Qualifier("gmailRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${spring.security.oauth2.client.registration.google.client-id:}") String clientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:}") String clientSecret) {
        this.connections = connections;
        this.encryption = encryption;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Transactional
    public String accessToken(Long connectionId) {
        GmailConnection connection = connections.findByIdForUpdate(connectionId)
                .orElseThrow(() -> new GmailAuthenticationRequiredException(
                        "connection_missing", RECONNECT_MESSAGE));
        Instant now = clock.instant();
        String accessToken = decrypt(connection.getAccessTokenEncrypted());
        if (accessToken != null && connection.getTokenExpiresAt() != null
                && connection.getTokenExpiresAt().isAfter(now.plus(EXPIRY_SKEW))) {
            return accessToken;
        }
        String refreshToken = decrypt(connection.getRefreshTokenEncrypted());
        if (refreshToken == null) {
            throw new GmailAuthenticationRequiredException("refresh_token_missing", RECONNECT_MESSAGE);
        }
        return refresh(connectionId, refreshToken, now);
    }

    private String refresh(Long connectionId, String refreshToken, Instant now) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            String body = restTemplate.postForObject(TOKEN_ENDPOINT, new HttpEntity<>(form, headers), String.class);
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            String newToken = json.path("access_token").asText("");
            if (newToken.isBlank()) {
                throw transientFailure(502, "token_refresh_invalid_response");
            }
            long expiresIn = json.path("expires_in").asLong(3600);
            connections.updateAccessToken(connectionId, encryption.encrypt(newToken), now.plusSeconds(expiresIn));
            return newToken;
        } catch (HttpStatusCodeException ex) {
            int status = ex.getStatusCode().value();
            String error = errorCode(ex.getResponseBodyAsString());
            if (status >= 400 && status < 500 && status != 429 && "invalid_grant".equals(error)) {
                throw new GmailAuthenticationRequiredException(error, RECONNECT_MESSAGE);
            }
            if (APPLICATION_CONFIGURATION_ERRORS.contains(error)) {
                throw transientFailure(503, "oauth_configuration_" + error);
            }
            throw transientFailure(status, "token_refresh_" + status);
        } catch (GmailApiException ex) {
            throw ex;
        } catch (RestClientException | java.io.IOException ex) {
            throw transientFailure(503, "token_refresh_unavailable");
        }
    }

    private String errorCode(String body) {
        try {
            return objectMapper.readTree(body).path("error").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    private GmailApiException transientFailure(int status, String reason) {
        return new GmailApiException(status >= 500 ? status : 503, reason, GmailApiOperation.PROFILE_GET, null);
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        String plain = encryption.decrypt(encrypted);
        return plain == null || plain.isBlank() ? null : plain;
    }
}
