package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
public class GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);
    private final GmailRequestLimiter limiter;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GmailApiClient(
            GmailRequestLimiter limiter,
            @org.springframework.beans.factory.annotation.Qualifier("gmailRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<String> get(
            Long connectionId,
            String accessToken,
            URI uri,
            GmailApiOperation operation,
            int retryAttempt) {
        return exchange(connectionId, accessToken, uri, operation, HttpMethod.GET, null, retryAttempt);
    }

    public ResponseEntity<String> post(
            Long connectionId,
            String accessToken,
            URI uri,
            String body,
            GmailApiOperation operation,
            int retryAttempt) {
        return exchange(connectionId, accessToken, uri, operation, HttpMethod.POST, body, retryAttempt);
    }

    private ResponseEntity<String> exchange(
            Long connectionId,
            String accessToken,
            URI uri,
            GmailApiOperation operation,
            HttpMethod method,
            String body,
            int retryAttempt) {
        try (GmailRequestLimiter.Permit ignored = limiter.acquire(connectionId, operation)) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (body != null) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
            return restTemplate.exchange(uri, method, new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException ex) {
            int status = ex.getStatusCode().value();
            String reason = parseReason(ex.getResponseBodyAsString());
            Instant retryAt = retryAfter(ex.getResponseHeaders());
            log.warn(
                    "Gmail API failure status={} reason={} limitClass={} operation={} connectionId={} attempt={} retryAt={}",
                    status, reason, new GmailApiException(status, reason, operation, retryAt).rateLimitClass(),
                    operation, connectionId, retryAttempt, retryAt);
            throw new GmailApiException(status, reason, operation, retryAt);
        } catch (DataAccessException ex) {
            throw new GmailBackfillException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "GMAIL_RATE_LIMITER_UNAVAILABLE",
                    "A Gmail-szinkronizáció védelmi szolgáltatása átmenetileg nem érhető el.");
        }
    }

    private String parseReason(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String reason = root.path("error").path("errors").path(0).path("reason").asText("");
            if (reason.isBlank()) {
                reason = root.path("error").path("status").asText("");
            }
            return reason.isBlank() ? "UNKNOWN" : reason.replaceAll("[^A-Za-z0-9_.-]", "_");
        } catch (Exception ex) {
            return "UNPARSEABLE";
        }
    }

    private Instant retryAfter(HttpHeaders headers) {
        if (headers == null || headers.getFirst(HttpHeaders.RETRY_AFTER) == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return Instant.now().plusSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }
}
