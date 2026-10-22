package com.buildassist.service;

import com.buildassist.dto.EmailDtos.BackfillResponse;
import com.buildassist.config.AppProperties;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.repository.EmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;

class GmailInboxSyncServiceTest {

    private static final String QUERY = "label:INBOX category:primary before:1792680000";
    private static final Instant NOW = Instant.parse("2026-10-22T18:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-04-22T18:00:00Z");
    private static final UUID LOCK_ID = UUID.randomUUID();

    private GmailBackfillStateStore stateStore;
    private GmailHistoryStateStore historyStateStore;
    private EmailRepository emailRepository;
    private GmailService gmailService;
    private EmailService emailService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private GmailInboxSyncService syncService;

    @BeforeEach
    void setUp() {
        stateStore = mock(GmailBackfillStateStore.class);
        historyStateStore = mock(GmailHistoryStateStore.class);
        emailRepository = mock(EmailRepository.class);
        gmailService = mock(GmailService.class);
        emailService = mock(EmailService.class);
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        syncService = new GmailInboxSyncService(
                stateStore,
                historyStateStore,
                gmailService,
                emailService,
                emailRepository,
                new GmailSyncWindow(new AppProperties(), Clock.fixed(NOW, ZoneOffset.UTC)),
                restTemplate,
                new ObjectMapper());
        when(gmailService.getOAuth2AccessToken("owner@gmail.com")).thenReturn(Optional.of("access-token"));
    }

    @Test
    void processesFirstPageAndPersistsNextPageTokenAfterStagingMessages() {
        claim(null);
        mockServer.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("maxResults=50"),
                        org.hamcrest.Matchers.containsString("q="))))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"messages":[{"id":"m1"}],"nextPageToken":"page-2"}
                        """, APPLICATION_JSON));
        mockSpamMessage("m1");
        when(stateStore.completePage(eq(7L), eq(LOCK_ID), any(), eq("page-2"))).thenReturn(1);

        BackfillResponse result = syncService.backfill(3L);

        assertEquals(1, result.importedCount());
        assertEquals(1, result.processedCount());
        assertTrue(result.hasMore());
        assertFalse(result.complete());
        verify(stateStore).completePage(eq(7L), eq(LOCK_ID), any(), eq("page-2"));
        mockServer.verify();
    }

    @Test
    void continuesThroughSubsequentFiftyMessageBatchesAndDetectsLastPage() {
        UUID secondLock = UUID.randomUUID();
        when(stateStore.claim(3L)).thenReturn(
                new GmailBackfillStateStore.Claim(
                        GmailBackfillStateStore.ClaimStatus.STARTED, LOCK_ID, 7L, "owner@gmail.com", null, QUERY),
                new GmailBackfillStateStore.Claim(
                        GmailBackfillStateStore.ClaimStatus.STARTED, secondLock, 7L, "owner@gmail.com", "page-2", QUERY));
        List<String> firstBatchIds = messageIds("first-", 50);
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withSuccess(messageListJson(firstBatchIds, "page-2"), APPLICATION_JSON));
        firstBatchIds.forEach(this::mockSpamMessage);
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("pageToken=page-2")))
                .andRespond(withSuccess(messageListJson(messageIds("second-", 50), null), APPLICATION_JSON));
        List<String> secondBatchIds = messageIds("second-", 50);
        secondBatchIds.forEach(this::mockSpamMessage);
        when(stateStore.completePage(eq(7L), eq(LOCK_ID), any(), eq("page-2"))).thenReturn(50);
        when(stateStore.completePage(eq(7L), eq(secondLock), any(), eq(null))).thenReturn(50);

        BackfillResponse first = syncService.backfill(3L);
        BackfillResponse second = syncService.backfill(3L);

        assertTrue(first.hasMore());
        assertTrue(second.complete());
        assertEquals(50, first.processedCount());
        assertEquals(50, second.processedCount());
        assertFalse(second.hasMore());
        verify(stateStore).completePage(eq(7L), eq(secondLock), any(), eq(null));
        mockServer.verify();
    }

    @Test
    void invalidContinuationTokenRestartsFromFirstPageWithoutChangingTheQuery() {
        claim("expired-token");
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("pageToken=expired-token")))
                .andRespond(withBadRequest().body("{\"error\":{\"message\":\"Invalid page token\"}}"));
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withSuccess("{\"messages\":[]}", APPLICATION_JSON));
        when(stateStore.completePage(eq(7L), eq(LOCK_ID), any(), eq(null))).thenReturn(0);

        BackfillResponse result = syncService.backfill(3L);

        assertTrue(result.complete());
        assertEquals(0, result.processedCount());
        verify(stateStore).completePage(eq(7L), eq(LOCK_ID), any(), eq(null));
        mockServer.verify();
    }

    @Test
    void failedPartialBatchDoesNotAdvanceTokenAndCanBeRetried() {
        UUID retryLock = UUID.randomUUID();
        when(stateStore.claim(3L)).thenReturn(
                new GmailBackfillStateStore.Claim(
                        GmailBackfillStateStore.ClaimStatus.STARTED, LOCK_ID, 7L, "owner@gmail.com", null, QUERY),
                new GmailBackfillStateStore.Claim(
                        GmailBackfillStateStore.ClaimStatus.STARTED, retryLock, 7L, "owner@gmail.com", null, QUERY));
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"good\"},{\"id\":\"failed\"}],\"nextPageToken\":\"next\"}", APPLICATION_JSON));
        mockSpamMessage("good");
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/messages/failed")))
                .andRespond(withTooManyRequests());

        GmailBackfillException failure = assertThrows(
                GmailBackfillException.class, () -> syncService.backfill(3L));

        assertEquals("GMAIL_RATE_LIMITED", failure.getCode());
        verify(stateStore, never()).completePage(any(), any(), any(), any());
        verify(stateStore).release(7L, LOCK_ID);
        mockServer.verify();

        mockServer.reset();
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"good\"}],\"nextPageToken\":\"next\"}", APPLICATION_JSON));
        mockSpamMessage("good");
        when(stateStore.completePage(eq(7L), eq(retryLock), any(), eq("next"))).thenReturn(1);

        BackfillResponse retried = syncService.backfill(3L);

        assertEquals(1, retried.importedCount());
        verify(stateStore).completePage(eq(7L), eq(retryLock), any(), eq("next"));
        mockServer.verify();
    }

    @Test
    void handlesGmailServerAndOAuthFailures() {
        claim(null);
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withServerError());
        GmailBackfillException serverFailure = assertThrows(
                GmailBackfillException.class, () -> syncService.backfill(3L));
        assertEquals(HttpStatus.BAD_GATEWAY, serverFailure.getStatus());
        mockServer.verify();

        mockServer.reset();
        claim(null);
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withUnauthorizedRequest());
        GmailBackfillException authFailure = assertThrows(
                GmailBackfillException.class, () -> syncService.backfill(3L));
        assertEquals(HttpStatus.FAILED_DEPENDENCY, authFailure.getStatus());
        verify(stateStore, never()).completePage(any(), any(), any(), any());
        mockServer.verify();

        mockServer.reset();
        claim(null);
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("""
                        {"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}
                        """));
        GmailBackfillException quotaFailure = assertThrows(
                GmailBackfillException.class, () -> syncService.backfill(3L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, quotaFailure.getStatus());
        assertEquals("GMAIL_RATE_LIMITED", quotaFailure.getCode());
        mockServer.verify();
    }

    @Test
    void missingOAuthTokenAndConcurrentBackfillDoNotCallGmail() {
        claim(null);
        when(gmailService.getOAuth2AccessToken("owner@gmail.com")).thenReturn(Optional.empty());
        GmailBackfillException authFailure = assertThrows(
                GmailBackfillException.class, () -> syncService.backfill(3L));
        assertEquals(HttpStatus.FAILED_DEPENDENCY, authFailure.getStatus());
        verify(stateStore).release(7L, LOCK_ID);
        mockServer.verify();

        mockServer.reset();
        when(stateStore.claim(4L)).thenReturn(new GmailBackfillStateStore.Claim(
                GmailBackfillStateStore.ClaimStatus.IN_PROGRESS, null, 7L, "owner@gmail.com", null, QUERY));
        BackfillResponse inProgress = syncService.backfill(4L);
        assertTrue(inProgress.inProgress());
        mockServer.verify();
    }

    @Test
    void aGmailFailureReleasesOnlyTheLeaseForTheFailingSync() {
        claim(null);
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("maxResults=50")))
                .andRespond(withServerError());

        assertThrows(GmailBackfillException.class, () -> syncService.backfill(3L));

        verify(stateStore).release(7L, LOCK_ID);
        verifyNoInteractions(historyStateStore);
        mockServer.verify();

        mockServer.reset();
        clearInvocations(stateStore);
        GmailHistoryStateStore.Claim historyClaim = new GmailHistoryStateStore.Claim(
                GmailHistoryStateStore.Status.CLAIMED,
                LOCK_ID,
                7L,
                "owner@gmail.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0);
        when(historyStateStore.claim(3L)).thenReturn(historyClaim);
        mockServer.expect(requestTo("https://www.googleapis.com/gmail/v1/users/me/profile"))
                .andRespond(withServerError());

        assertThrows(GmailBackfillException.class, () -> syncService.refresh(3L));

        verify(historyStateStore).release(7L, LOCK_ID);
        verifyNoInteractions(stateStore);
        mockServer.verify();
    }

    @Test
    void backfillChecksExactSixMonthBoundaryBeforeImporting() {
        GmailSyncWindow window = new GmailSyncWindow(
                new AppProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(stateStore.claim(3L)).thenReturn(new GmailBackfillStateStore.Claim(
                GmailBackfillStateStore.ClaimStatus.STARTED,
                LOCK_ID,
                7L,
                "owner@gmail.com",
                null,
                window.queryFor(CUTOFF),
                CUTOFF));
        mockServer.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("maxResults=50"),
                        org.hamcrest.Matchers.containsString("after:"))))
                .andRespond(withSuccess("""
                        {"messages":[{"id":"before"},{"id":"exact"},{"id":"after"}]}
                        """, APPLICATION_JSON));
        mockSpamMetadataAt("before", CUTOFF.minusMillis(1));
        mockSpamMetadataAt("exact", CUTOFF);
        mockSpamMetadataAt("after", CUTOFF.plusMillis(1));
        when(stateStore.completePage(eq(7L), eq(LOCK_ID), any(), eq(null))).thenReturn(2);

        BackfillResponse response = syncService.backfill(3L);

        assertEquals(2, response.importedCount());
        assertEquals(3, response.processedCount());
        org.mockito.ArgumentCaptor<List<Email>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(stateStore).completePage(eq(7L), eq(LOCK_ID), captor.capture(), eq(null));
        assertEquals(List.of("exact", "after"),
                captor.getValue().stream().map(Email::getGmailMessageId).toList());
        mockServer.verify();
    }

    private void claim(String pageToken) {
        when(stateStore.claim(3L)).thenReturn(new GmailBackfillStateStore.Claim(
                GmailBackfillStateStore.ClaimStatus.STARTED, LOCK_ID, 7L, "owner@gmail.com", pageToken, QUERY));
    }

    private List<String> messageIds(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> prefix + index)
                .toList();
    }

    private String messageListJson(List<String> messageIds, String nextPageToken) {
        String messages = messageIds.stream()
                .map(id -> "{\"id\":\"" + id + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        String next = nextPageToken == null ? "" : ",\"nextPageToken\":\"" + nextPageToken + "\"";
        return "{\"messages\":[" + messages + "]" + next + "}";
    }

    private void mockSpamMessage(String messageId) {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/messages/" + messageId)))
                .andRespond(withSuccess("""
                        {"internalDate":"1792680000000","payload":{"headers":[
                          {"name":"Subject","value":"Newsletter"},
                          {"name":"From","value":"news@example.com"},
                          {"name":"List-ID","value":"news.example.com"}
                        ]}}
                        """, APPLICATION_JSON));
        when(emailService.categorizeMessage(any(), eq(messageId))).thenReturn(EmailCategory.SPAM);
        when(emailService.createEmailFromGmailResponse(eq(null), eq(messageId), any(), any(), eq(EmailCategory.SPAM)))
                .thenAnswer(invocation -> {
                    Email email = new Email();
                    email.setGmailMessageId(messageId);
                    email.setSubject("Newsletter");
                    email.setFromAddress("news@example.com");
                    email.setBodyText("");
                    email.setCategory(EmailCategory.SPAM);
                    email.setEmailReceivedAt(java.time.OffsetDateTime.parse("2026-10-22T00:00:00Z"));
                    return email;
                });
    }

    private void mockSpamMetadataAt(String messageId, Instant receivedAt) {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/messages/" + messageId)))
                .andRespond(withSuccess("""
                        {"internalDate":"%d","payload":{"headers":[
                          {"name":"Subject","value":"Newsletter"},
                          {"name":"From","value":"news@example.com"},
                          {"name":"List-ID","value":"news.example.com"}
                        ]}}
                        """.formatted(receivedAt.toEpochMilli()), APPLICATION_JSON));
        when(emailService.categorizeMessage(any(), eq(messageId))).thenReturn(EmailCategory.SPAM);
        when(emailService.createEmailFromGmailResponse(eq(null), eq(messageId), any(), any(), eq(EmailCategory.SPAM)))
                .thenAnswer(invocation -> {
                    Email email = new Email();
                    email.setGmailMessageId(messageId);
                    email.setCategory(EmailCategory.SPAM);
                    email.setEmailReceivedAt(OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
                    return email;
                });
    }
}
