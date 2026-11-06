package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.dto.GmailDtos.GmailConnectResponse;
import com.buildassist.dto.GmailDtos.GmailStatusResponse;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.GmailConnectionRepository;
import com.buildassist.repository.UserRepository;
import com.buildassist.security.FrontendRedirectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GmailServiceOAuthTest {

    private static final long USER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-10-22T18:00:00Z");
    private static final String READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String COMPOSE_SCOPE = "https://www.googleapis.com/auth/gmail.compose";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json";

    private AppProperties properties;
    private GmailConnectionRepository connections;
    private UserRepository users;
    private TokenEncryptionService encryption;
    private GmailOAuthStateService oauthState;
    private MockRestServiceServer server;
    private GmailService service;
    private User user;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getJwt().setSecret("0123456789abcdef0123456789abcdef");
        properties.getEncryption().setAesKey("abcdef0123456789abcdef0123456789");
        properties.getGmail().setClientId("gmail-client");
        properties.getGmail().setClientSecret("gmail-secret");
        properties.getGmail().setRedirectUri("http://localhost:8080/api/gmail/callback");
        properties.setFrontendUrl("http://localhost:3000");
        properties.getCors().setAllowedOrigins("http://localhost:3000");

        connections = mock(GmailConnectionRepository.class);
        users = mock(UserRepository.class);
        encryption = new TokenEncryptionService(properties);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        oauthState = new GmailOAuthStateService(properties, clock);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new GmailService(
                properties, connections, users, encryption, oauthState, restTemplate, new ObjectMapper(), clock);

        user = new User();
        user.setId(USER_ID);
        user.setEmail("mira-user@example.com");
        user.setActive(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void explicitGmailAuthorizationRequestHasOfflineConsentAndRequiredScopes() {
        GmailConnectResponse response = service.getAuthorizationUrl(USER_ID);
        var uri = UriComponentsBuilder.fromUriString(response.authorizationUrl()).build(true);
        var query = uri.getQueryParams();

        assertEquals("http://localhost:8080/api/gmail/callback", query.getFirst("redirect_uri"));
        assertEquals("offline", query.getFirst("access_type"));
        assertEquals("consent", query.getFirst("prompt"));
        String scope = java.net.URLDecoder.decode(
                query.getFirst("scope"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(Set.of(scope.split(" ")).containsAll(
                Set.of("openid", "email", "profile", READ_SCOPE, COMPOSE_SCOPE)));
        assertEquals(USER_ID, oauthState.verify(query.getFirst("state")));
        assertFalse(String.valueOf(USER_ID).equals(query.getFirst("state")));
    }

    @Test
    void legacyLoginCallbackConfigurationIsNormalizedToTheExplicitGmailCallback() {
        properties.getGmail().setRedirectUri("http://localhost:8080/login/oauth2/code/google");

        GmailConnectResponse response = service.getAuthorizationUrl(USER_ID);
        var query = UriComponentsBuilder.fromUriString(response.authorizationUrl()).build(true).getQueryParams();

        assertEquals("http://localhost:8080/api/gmail/callback", query.getFirst("redirect_uri"));
    }

    @Test
    void successfulExplicitConnectEncryptsTokensClearsReconnectStateAndPreservesSyncCursor() {
        GmailConnection connection = existingConnection(true);
        when(connections.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(connection));
        expectSuccessfulGoogleResponses("""
                {"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"scope":"openid email profile %s %s"}
                """.formatted(READ_SCOPE, COMPOSE_SCOPE));

        service.handleOAuthCallback("one-time-code", oauthState.issue(USER_ID));

        verify(connections).save(connection);
        assertEquals(USER_ID, connection.getUser().getId());
        assertEquals("gmail-account@gmail.com", connection.getGmailAddress());
        assertEquals("new-access", encryption.decrypt(connection.getAccessTokenEncrypted()));
        assertEquals("new-refresh", encryption.decrypt(connection.getRefreshTokenEncrypted()));
        assertEquals(NOW.plusSeconds(3600), connection.getTokenExpiresAt());
        assertFalse(connection.isGmailSyncAuthRequired());
        assertEquals(null, connection.getGmailSyncAuthErrorCategory());
        assertEquals(null, connection.getGmailSyncAuthRequiredAt());
        assertEquals("history-cursor-42", connection.getHistorySyncCursor());
        assertEquals("history-page-7", connection.getHistorySyncPageToken());
        server.verify();
    }

    @Test
    void newConnectionWithoutRefreshTokenIsNotMarkedConnected() {
        when(connections.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        expectSuccessfulGoogleResponses("""
                {"access_token":"access-only","expires_in":3600,"scope":"openid email profile %s %s"}
                """.formatted(READ_SCOPE, COMPOSE_SCOPE));

        GmailOAuthException exception = assertThrows(GmailOAuthException.class,
                () -> service.handleOAuthCallback("one-time-code", oauthState.issue(USER_ID)));

        assertEquals("refresh_token_missing", exception.getCategory());
        verify(connections, never()).save(any());
        server.verify();
    }

    @Test
    void refreshTokenIsPreservedOnlyForSamePreviouslyConnectedGmailAccount() {
        GmailConnection connection = existingConnection(false);
        when(connections.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(connection));
        expectSuccessfulGoogleResponses("""
                {"access_token":"rotated-access","expires_in":3600,"scope":"openid email profile %s %s"}
                """.formatted(READ_SCOPE, COMPOSE_SCOPE));

        service.handleOAuthCallback("one-time-code", oauthState.issue(USER_ID));

        assertEquals("old-refresh", encryption.decrypt(connection.getRefreshTokenEncrypted()));
        assertEquals("rotated-access", encryption.decrypt(connection.getAccessTokenEncrypted()));
        verify(connections).save(connection);
        server.verify();
    }

    @Test
    void reconnectWithoutANewRefreshTokenCannotReuseBlockedCredential() {
        GmailConnection connection = existingConnection(true);
        when(connections.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(connection));
        expectSuccessfulGoogleResponses("""
                {"access_token":"access-only","expires_in":3600,"scope":"openid email profile %s %s"}
                """.formatted(READ_SCOPE, COMPOSE_SCOPE));

        GmailOAuthException exception = assertThrows(GmailOAuthException.class,
                () -> service.handleOAuthCallback("one-time-code", oauthState.issue(USER_ID)));

        assertEquals("refresh_token_missing", exception.getCategory());
        assertTrue(connection.isGmailSyncAuthRequired());
        assertEquals("old-refresh", encryption.decrypt(connection.getRefreshTokenEncrypted()));
        verify(connections, never()).save(any());
        server.verify();
    }

    @Test
    void reconnectRequiredStatusPreservesAddressAndAdvertisesTheReconnectAction() {
        GmailConnection connection = existingConnection(true);
        when(connections.findByUserId(USER_ID)).thenReturn(Optional.of(connection));

        GmailStatusResponse status = service.getConnectionStatus(USER_ID);

        assertFalse(status.connected());
        assertTrue(status.reconnectRequired());
        assertEquals("gmail-account@gmail.com", status.gmailAddress());
    }

    @Test
    void invalidClientIsReportedAsConfigurationFailureWithoutChangingConnection() {
        GmailConnection connection = existingConnection(false);
        when(connections.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(connection));
        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        GmailOAuthException exception = assertThrows(GmailOAuthException.class,
                () -> service.handleOAuthCallback("one-time-code", oauthState.issue(USER_ID)));

        assertEquals("oauth_configuration_invalid_client", exception.getCategory());
        assertEquals("old-refresh", encryption.decrypt(connection.getRefreshTokenEncrypted()));
        verify(connections, never()).save(any());
        server.verify();
    }

    @Test
    void missingGmailScopeAndTamperedStateDoNotPersistConnection() {
        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"access-only","refresh_token":"refresh","scope":"openid email"}
                        """, MediaType.APPLICATION_JSON));
        GmailOAuthException missingScope = assertThrows(GmailOAuthException.class,
                () -> service.handleOAuthCallback("one-time-code", oauthState.issue(USER_ID)));
        assertEquals("gmail_scope_missing", missingScope.getCategory());

        String validState = oauthState.issue(USER_ID);
        String[] stateParts = validState.split("\\.", -1);
        String changedSignature = (stateParts[1].charAt(0) == 'A' ? "B" : "A")
                + stateParts[1].substring(1);
        String tamperedState = stateParts[0] + "." + changedSignature;
        assertThrows(GmailOAuthException.class,
                () -> service.handleOAuthCallback("one-time-code", tamperedState));
        verify(connections, never()).save(any());
        server.verify();
    }

    private GmailConnection existingConnection(boolean reconnectRequired) {
        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        connection.setGmailAddress("gmail-account@gmail.com");
        connection.setAccessTokenEncrypted(encryption.encrypt("old-access"));
        connection.setRefreshTokenEncrypted(encryption.encrypt("old-refresh"));
        connection.setTokenExpiresAt(NOW.minusSeconds(30));
        connection.setGmailSyncAuthRequired(reconnectRequired);
        connection.setGmailSyncAuthErrorCategory(reconnectRequired ? "invalid_grant" : null);
        connection.setGmailSyncAuthRequiredAt(reconnectRequired ? NOW.minusSeconds(60) : null);
        connection.setGmailSyncRetryAttempts(4);
        connection.setGmailSyncRetryAt(NOW.plusSeconds(60));
        connection.setGmailSyncRetryExhausted(reconnectRequired);
        connection.setHistorySyncCursor("history-cursor-42");
        connection.setHistorySyncPageToken("history-page-7");
        return connection;
    }

    private void expectSuccessfulGoogleResponses(String tokenResponse) {
        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andRespond(withSuccess(tokenResponse, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERINFO_ENDPOINT))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer "
                        + (tokenResponse.contains("new-access") ? "new-access"
                        : tokenResponse.contains("access-only") ? "access-only" : "rotated-access")))
                .andRespond(withSuccess("{\"email\":\"gmail-account@gmail.com\"}", MediaType.APPLICATION_JSON));
    }
}
