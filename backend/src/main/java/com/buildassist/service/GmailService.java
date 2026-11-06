package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.dto.GmailDtos.GmailConnectResponse;
import com.buildassist.dto.GmailDtos.GmailStatusResponse;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.GmailConnectionRepository;
import com.buildassist.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GmailService {

    private static final String GOOGLE_AUTHORIZATION_ENDPOINT =
            "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO_ENDPOINT =
            "https://www.googleapis.com/oauth2/v1/userinfo?alt=json";
    private static final String GMAIL_CALLBACK_PATH = "/api/gmail/callback";
    private static final String GMAIL_READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String GMAIL_COMPOSE_SCOPE = "https://www.googleapis.com/auth/gmail.compose";
    private static final Set<String> REQUIRED_SCOPES =
            Set.of("openid", "email", "profile", GMAIL_READ_SCOPE, GMAIL_COMPOSE_SCOPE);

    private final AppProperties appProperties;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final UserRepository userRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final GmailOAuthStateService oauthStateService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GmailService(
            AppProperties appProperties,
            GmailConnectionRepository gmailConnectionRepository,
            UserRepository userRepository,
            TokenEncryptionService tokenEncryptionService,
            GmailOAuthStateService oauthStateService,
            @Qualifier("gmailRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        this.appProperties = appProperties;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.userRepository = userRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.oauthStateService = oauthStateService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public GmailConnectResponse getAuthorizationUrl(Long userId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new GmailOAuthException("user_unavailable"));
        String clientId = appProperties.getGmail().getClientId();
        String redirectUri = gmailCallbackUri();
        if (clientId == null || clientId.isBlank()) {
            throw new GmailOAuthException("oauth_configuration_missing_client_id");
        }

        String url = UriComponentsBuilder.fromUriString(GOOGLE_AUTHORIZATION_ENDPOINT)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", REQUIRED_SCOPES))
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", oauthStateService.issue(user.getId()))
                .build()
                .encode()
                .toUriString();
        return new GmailConnectResponse(url);
    }

    public Long validateOAuthState(String state) {
        try {
            return oauthStateService.verify(state);
        } catch (IllegalArgumentException ex) {
            throw new GmailOAuthException("invalid_state", ex);
        }
    }

    @Transactional
    public void handleOAuthCallback(String code, String state) {
        if (code == null || code.isBlank()) {
            throw new GmailOAuthException("authorization_code_missing");
        }
        Long userId = validateOAuthState(state);
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new GmailOAuthException("user_unavailable"));

        JsonNode tokenJson = exchangeAuthorizationCode(code);
        String accessToken = tokenJson.path("access_token").asText("");
        String refreshToken = tokenJson.path("refresh_token").asText("");
        if (accessToken.isBlank()) {
            throw new GmailOAuthException("access_token_missing");
        }
        verifyGrantedScopes(tokenJson.path("scope").asText(""));

        JsonNode userInfo = fetchUserInfo(accessToken);
        String gmailAddress = userInfo.path("email").asText("");
        if (gmailAddress.isBlank()) {
            throw new GmailOAuthException("gmail_address_missing");
        }

        GmailConnection connection = gmailConnectionRepository.findByUserIdForUpdate(userId)
                .orElseGet(GmailConnection::new);
        boolean reconnectWasRequired = connection.isGmailSyncAuthRequired();
        String storedRefreshToken = connection.getRefreshTokenEncrypted();
        if (refreshToken.isBlank()) {
            boolean sameGmailAccount = connection.getGmailAddress() != null
                    && connection.getGmailAddress().equalsIgnoreCase(gmailAddress);
            if (reconnectWasRequired || !sameGmailAccount
                    || storedRefreshToken == null || storedRefreshToken.isBlank()) {
                throw new GmailOAuthException("refresh_token_missing");
            }
        } else {
            connection.setRefreshTokenEncrypted(tokenEncryptionService.encrypt(refreshToken));
        }

        connection.setUser(user);
        connection.setGmailAddress(gmailAddress);
        connection.setAccessTokenEncrypted(tokenEncryptionService.encrypt(accessToken));
        long expiresIn = tokenJson.path("expires_in").asLong(3600);
        connection.setTokenExpiresAt(clock.instant().plusSeconds(Math.max(1, expiresIn)));
        connection.setGmailSyncAuthRequired(false);
        connection.setGmailSyncAuthErrorCategory(null);
        connection.setGmailSyncAuthRequiredAt(null);
        connection.setGmailSyncRetryAttempts(0);
        connection.setGmailSyncRetryAt(null);
        connection.setGmailSyncRetryExhausted(false);
        gmailConnectionRepository.save(connection);
    }

    public GmailStatusResponse getConnectionStatus(Long userId) {
        Optional<GmailConnection> optional = gmailConnectionRepository.findByUserId(userId);
        if (optional.isEmpty()) {
            return new GmailStatusResponse(false, null, false);
        }
        GmailConnection connection = optional.get();
        boolean reconnectRequired = connection.isGmailSyncAuthRequired();
        boolean connected = !reconnectRequired
                && connection.getRefreshTokenEncrypted() != null
                && !connection.getRefreshTokenEncrypted().isBlank();
        return new GmailStatusResponse(connected, connection.getGmailAddress(), reconnectRequired);
    }

    private JsonNode exchangeAuthorizationCode(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", appProperties.getGmail().getClientId());
        form.add("client_secret", appProperties.getGmail().getClientSecret());
        form.add("redirect_uri", gmailCallbackUri());
        form.add("grant_type", "authorization_code");
        try {
            String body = restTemplate.postForObject(
                    GOOGLE_TOKEN_ENDPOINT, new HttpEntity<>(form, headers), String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (HttpStatusCodeException ex) {
            String error = parseGoogleError(ex.getResponseBodyAsString());
            if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                throw new GmailOAuthException("oauth_configuration_" + error);
            }
            throw new GmailOAuthException("token_exchange_" + (error.isBlank() ? ex.getStatusCode().value() : error));
        } catch (RestClientException | java.io.IOException ex) {
            throw new GmailOAuthException("token_exchange_unavailable", ex);
        }
    }

    private JsonNode fetchUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        try {
            String body = restTemplate.exchange(
                    GOOGLE_USERINFO_ENDPOINT, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (HttpStatusCodeException ex) {
            throw new GmailOAuthException("userinfo_" + ex.getStatusCode().value());
        } catch (RestClientException | java.io.IOException ex) {
            throw new GmailOAuthException("userinfo_unavailable", ex);
        }
    }

    private void verifyGrantedScopes(String scopeValue) {
        if (scopeValue == null || scopeValue.isBlank()) {
            throw new GmailOAuthException("gmail_scope_unverified");
        }
        Set<String> granted = new HashSet<>(Arrays.asList(scopeValue.trim().split("\\s+")));
        if (!granted.contains(GMAIL_READ_SCOPE) || !granted.contains(GMAIL_COMPOSE_SCOPE)) {
            throw new GmailOAuthException("gmail_scope_missing");
        }
    }

    private String parseGoogleError(String responseBody) {
        try {
            return objectMapper.readTree(responseBody).path("error").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    private String gmailCallbackUri() {
        try {
            String configuredUri = appProperties.getGmail().getRedirectUri();
            if (configuredUri == null || configuredUri.isBlank()) {
                throw new GmailOAuthException("oauth_configuration_missing_gmail_callback_origin");
            }
            java.net.URI uri = java.net.URI.create(configuredUri);
            if (uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new GmailOAuthException("oauth_configuration_invalid_gmail_callback_origin");
            }
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    GMAIL_CALLBACK_PATH, null, null).toASCIIString();
        } catch (GmailOAuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GmailOAuthException("oauth_configuration_invalid_gmail_callback_origin", ex);
        }
    }
}
