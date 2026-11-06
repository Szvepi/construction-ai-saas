package com.buildassist.service;

import com.buildassist.model.GmailConnection;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GmailTokenProviderTest {

    private static final Long CONNECTION_ID = 5L;
    private static final Instant NOW = Instant.parse("2026-10-22T18:00:00Z");
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private GmailConnectionRepository connections;
    private TokenEncryptionService encryption;
    private MockRestServiceServer server;
    private GmailTokenProvider provider;
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        connections = mock(GmailConnectionRepository.class);
        encryption = mock(TokenEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).substring(4));
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        provider = new GmailTokenProvider(
                connections, encryption, restTemplate, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), "client-id", "client-secret");
        connection = new GmailConnection();
        connection.setAccessTokenEncrypted("enc:stored-access");
        connection.setRefreshTokenEncrypted("enc:stored-refresh");
        connection.setTokenExpiresAt(NOW.plusSeconds(3600));
        when(connections.findByIdForUpdate(CONNECTION_ID)).thenReturn(Optional.of(connection));
    }

    @Test
    void validStoredAccessTokenIsUsedWithoutAnySessionOrNetworkCall() {
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        assertEquals("stored-access", provider.accessToken(CONNECTION_ID));

        server.verify();
        verify(connections).findByIdForUpdate(CONNECTION_ID);
        verify(connections, never()).updateAccessToken(any(), any(), any());
    }

    @Test
    void tokenRefreshUsesATransactionalPessimisticConnectionLock() throws Exception {
        assertNotNull(GmailTokenProvider.class.getMethod("accessToken", Long.class)
                .getAnnotation(Transactional.class));
        assertEquals(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE,
                GmailConnectionRepository.class.getMethod("findByIdForUpdate", Long.class)
                        .getAnnotation(Lock.class).value());
    }

    @Test
    void expiredAccessTokenIsRefreshedAndPersisted() {
        connection.setTokenExpiresAt(NOW.minusSeconds(10));
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=refresh_token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("refresh_token=stored-refresh")))
                .andRespond(withSuccess("{\"access_token\":\"fresh\",\"expires_in\":3599}", MediaType.APPLICATION_JSON));

        assertEquals("fresh", provider.accessToken(CONNECTION_ID));

        verify(connections).updateAccessToken(CONNECTION_ID, "enc:fresh", NOW.plusSeconds(3599));
        server.verify();
    }

    @Test
    void invalidGrantRequiresReconnect() {
        connection.setTokenExpiresAt(NOW.minusSeconds(10));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));

        GmailAuthenticationRequiredException ex = assertThrows(
                GmailAuthenticationRequiredException.class, () -> provider.accessToken(CONNECTION_ID));

        assertEquals("invalid_grant", ex.getReason());
        assertEquals("GMAIL_AUTHENTICATION_REQUIRED", ex.getCode());
        verify(connections, never()).updateAccessToken(any(), any(), any());
    }

    @Test
    void invalidClientIsAnApplicationConfigurationFailureNotAReconnectRequest() {
        connection.setTokenExpiresAt(NOW.minusSeconds(10));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_client\"}"));

        GmailApiException ex = assertThrows(
                GmailApiException.class, () -> provider.accessToken(CONNECTION_ID));

        assertEquals(503, ex.getStatus());
        assertEquals("oauth_configuration_invalid_client", ex.getReason());
        verify(connections, never()).updateAccessToken(any(), any(), any());
    }

    @Test
    void missingRefreshTokenWithExpiredAccessRequiresReconnectWithoutNetworkCall() {
        connection.setTokenExpiresAt(NOW.minusSeconds(10));
        connection.setRefreshTokenEncrypted("enc:");

        GmailAuthenticationRequiredException ex = assertThrows(
                GmailAuthenticationRequiredException.class, () -> provider.accessToken(CONNECTION_ID));

        assertEquals("refresh_token_missing", ex.getReason());
        server.verify();
    }

    @Test
    void googleServerErrorAndRateLimitAreTransient() {
        connection.setTokenExpiresAt(NOW.minusSeconds(10));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));

        GmailApiException first = assertThrows(GmailApiException.class, () -> provider.accessToken(CONNECTION_ID));
        GmailApiException second = assertThrows(GmailApiException.class, () -> provider.accessToken(CONNECTION_ID));

        assertEquals(true, first.isTransientServerError());
        assertEquals(true, second.isTransientServerError());
        verify(connections, never()).updateAccessToken(any(), any(), eq(NOW));
    }
}
