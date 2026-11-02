package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.dto.EmailDtos.RefreshEmailsResponse;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.repository.EmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GmailInboxSyncServiceTest {

    private static final Long USER_ID = 3L;
    private static final Long CONNECTION_ID = 7L;
    private static final UUID LOCK_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T18:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-04-22T18:00:00Z");
    private static final String QUERY = "label:INBOX category:primary before:1792680000";

    private GmailHistoryStateStore historyStateStore;
    private EmailRepository emailRepository;
    private GmailService gmailService;
    private EmailService emailService;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GmailInboxSyncService syncService;
    private AppProperties properties;
    private List<GmailApiOperation> apiOperations;

    @BeforeEach
    void setUp() {
        historyStateStore = mock(GmailHistoryStateStore.class);
        emailRepository = mock(EmailRepository.class);
        gmailService = mock(GmailService.class);
        emailService = mock(EmailService.class);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new AppProperties();
        apiOperations = new java.util.ArrayList<>();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        syncService = new GmailInboxSyncService(
                historyStateStore,
                gmailService,
                emailService,
                emailRepository,
                new GmailSyncWindow(properties, clock),
                new GmailApiClient((connectionId, operation) -> {
                    apiOperations.add(operation);
                    return () -> { };
                }, restTemplate, new ObjectMapper()),
                new ObjectMapper(),
                new GmailRetryPolicy(properties),
                clock);
        when(gmailService.getOAuth2AccessToken("owner@gmail.com")).thenReturn(Optional.of("access-token"));
    }

    @Test
    void aNewUserCanImportFiftyMessagesAndPersistProgressInSmallCheckpoints() {
        when(historyStateStore.claim(USER_ID)).thenReturn(claim(null, null, null, List.of(), 0, null));
        server.expect(requestTo("https://www.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"historyId\":\"h-start\"}", APPLICATION_JSON));
        List<String> ids = messageIds(50);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess(messageListJson(ids, "page-2"), APPLICATION_JSON));
        ids.forEach(this::mockSpamMetadata);
        when(historyStateStore.persistPartialEmails(eq(CONNECTION_ID), eq(LOCK_ID), any())).thenReturn(10);
        when(historyStateStore.completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-2")))
                .thenReturn(0);

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertEquals(50, response.fetchedCount());
        assertEquals(50, response.processedCount());
        assertTrue(response.hasMore());
        assertFalse(response.complete());
        verify(historyStateStore, org.mockito.Mockito.times(5))
                .persistPartialEmails(eq(CONNECTION_ID), eq(LOCK_ID), any());
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-2"));
        assertEquals(50, apiOperations.stream()
                .filter(operation -> operation == GmailApiOperation.MESSAGES_GET_FULL).count());
        assertEquals(1, apiOperations.stream()
                .filter(operation -> operation == GmailApiOperation.MESSAGES_LIST).count());
        assertEquals(1, apiOperations.stream()
                .filter(operation -> operation == GmailApiOperation.PROFILE_GET).count());
        assertEquals(3_006, apiOperations.stream()
                .mapToInt(operation -> operation.quotaUnits(properties.getGmail())).sum());
        server.verify();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void initialImportFollowsEveryPageAndCompletesAfterFiftyPlusFiftyPlusFortyThreeMessages(
            CapturedOutput output) {
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim(null, null, null, List.of(), 0, null))
                .thenReturn(claimWithPageToken("BOOTSTRAP", "page-2"))
                .thenReturn(claimWithPageToken("BOOTSTRAP", "page-3"));
        server.expect(requestTo("https://www.googleapis.com/gmail/v1/users/me/profile"))
                .andRespond(withSuccess("{\"historyId\":\"h-start\"}", APPLICATION_JSON));
        List<String> firstPage = messageIds(50);
        List<String> secondPage = java.util.stream.IntStream.range(50, 100)
                .mapToObj(index -> "m" + index).toList();
        List<String> lastPage = java.util.stream.IntStream.range(100, 143)
                .mapToObj(index -> "m" + index).toList();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess(messageListJson(firstPage, "page-2"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("pageToken=page-2")))
                .andRespond(withSuccess(messageListJson(secondPage, "page-3"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("pageToken=page-3")))
                .andRespond(withSuccess(messageListJson(lastPage, null), APPLICATION_JSON));
        java.util.stream.Stream.of(firstPage, secondPage, lastPage)
                .flatMap(List::stream)
                .forEach(this::mockSpamMetadata);
        when(historyStateStore.persistPartialEmails(eq(CONNECTION_ID), eq(LOCK_ID), any())).thenReturn(10);
        when(historyStateStore.completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), any()))
                .thenReturn(0, 0, 3);

        RefreshEmailsResponse first = syncService.refresh(USER_ID);
        RefreshEmailsResponse second = syncService.refresh(USER_ID);
        RefreshEmailsResponse last = syncService.refresh(USER_ID);

        assertEquals(50, first.fetchedCount());
        assertTrue(first.hasMore());
        assertFalse(first.complete());
        assertEquals(50, second.fetchedCount());
        assertTrue(second.hasMore());
        assertFalse(second.complete());
        assertEquals(43, last.fetchedCount());
        assertEquals(43, last.processedCount());
        assertFalse(last.hasMore());
        assertTrue(last.complete());
        assertEquals("INITIAL_COMPLETE", last.phase());
        assertEquals(143, first.fetchedCount() + second.fetchedCount() + last.fetchedCount());
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-2"));
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-3"));
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(null));
        assertTrue(output.getOut().contains("SYNC_STARTED event=SYNC_STARTED"));
        assertTrue(output.getOut().contains("GMAIL_PAGE_REQUESTED event=GMAIL_PAGE_REQUESTED"));
        assertTrue(output.getOut().contains("GMAIL_PAGE_RECEIVED event=GMAIL_PAGE_RECEIVED"));
        assertTrue(output.getOut().contains("SYNC_RESUMED event=SYNC_RESUMED"));
        assertTrue(output.getOut().contains("SYNC_PAGE_SAVED event=SYNC_PAGE_SAVED"));
        assertTrue(output.getOut().contains("SYNC_COMPLETED event=SYNC_COMPLETED"));
        assertFalse(output.getOut().contains("pageToken=page-2"));
        assertFalse(output.getOut().contains("owner@gmail.com"));
        server.verify();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aPageWithNoNewMessagesStillPersistsAndFollowsItsNextPageToken(CapturedOutput output) {
        List<String> knownIds = messageIds(50);
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null))
                .thenReturn(claimWithPageToken("BOOTSTRAP", "page-2"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess(messageListJson(knownIds, "page-2"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("pageToken=page-2")))
                .andRespond(withSuccess(messageListJson(List.of("new-message"), null), APPLICATION_JSON));
        knownIds.forEach(id -> when(emailRepository
                .existsByGmailConnectionIdAndGmailMessageId(CONNECTION_ID, id)).thenReturn(true));
        when(emailRepository.existsByGmailConnectionIdAndGmailMessageId(CONNECTION_ID, "new-message"))
                .thenReturn(false);
        mockSpamMetadata("new-message");
        when(historyStateStore.completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), any()))
                .thenReturn(0, 1);

        RefreshEmailsResponse first = syncService.refresh(USER_ID);
        RefreshEmailsResponse second = syncService.refresh(USER_ID);

        assertEquals(0, first.fetchedCount());
        assertEquals(50, first.processedCount());
        assertTrue(first.hasMore());
        assertFalse(first.complete());
        assertEquals(1, second.fetchedCount());
        assertTrue(second.complete());
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-2"));
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(null));
        assertTrue(output.getOut().contains("GMAIL_MESSAGE_SKIPPED event=GMAIL_MESSAGE_SKIPPED"));
        server.verify();
    }

    @Test
    void anEmptyMessagesArrayWithNextPageTokenContinuesTheScan() {
        when(historyStateStore.claim(USER_ID)).thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess("{\"messages\":[],\"nextPageToken\":\"page-2\"}", APPLICATION_JSON));
        when(historyStateStore.completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-2")))
                .thenReturn(0);

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertEquals(0, response.processedCount());
        assertTrue(response.hasMore());
        assertFalse(response.complete());
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-2"));
        server.verify();
    }

    @Test
    void malformedListPageCannotAdvanceThePersistedCursor() {
        when(historyStateStore.claim(USER_ID)).thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess("""
                        {"messages":{},"nextPageToken":"page-2"}
                        """, APPLICATION_JSON));

        GmailBackfillException exception = assertThrows(
                GmailBackfillException.class, () -> syncService.refresh(USER_ID));

        assertEquals("GMAIL_INVALID_RESPONSE", exception.getCode());
        verify(historyStateStore, never()).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), any());
        verify(historyStateStore).release(eq(CONNECTION_ID), eq(LOCK_ID));
        server.verify();
    }

    @Test
    void aSecondPageWithNextPageTokenDoesNotCompleteAfterNinetyThreeMessages() {
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null))
                .thenReturn(claimWithPageToken("BOOTSTRAP", "page-2"))
                .thenReturn(claimWithPageToken("BOOTSTRAP", "page-3"));
        List<String> firstPage = messageIds(50);
        List<String> secondPage = java.util.stream.IntStream.range(50, 93)
                .mapToObj(index -> "m" + index).toList();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess(messageListJson(firstPage, "page-2"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("pageToken=page-2")))
                .andRespond(withSuccess(messageListJson(secondPage, "page-3"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("pageToken=page-3")))
                .andRespond(withSuccess("{\"messages\":[]}", APPLICATION_JSON));
        java.util.stream.Stream.concat(firstPage.stream(), secondPage.stream())
                .forEach(this::mockSpamMetadata);
        when(historyStateStore.persistPartialEmails(eq(CONNECTION_ID), eq(LOCK_ID), any())).thenReturn(10);
        when(historyStateStore.completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), any()))
                .thenReturn(0, 3, 0);

        RefreshEmailsResponse first = syncService.refresh(USER_ID);
        RefreshEmailsResponse second = syncService.refresh(USER_ID);
        RefreshEmailsResponse last = syncService.refresh(USER_ID);

        assertEquals(50, first.fetchedCount());
        assertTrue(first.hasMore());
        assertEquals(43, second.fetchedCount());
        assertTrue(second.hasMore());
        assertFalse(second.complete());
        assertEquals(0, last.fetchedCount());
        assertFalse(last.hasMore());
        assertTrue(last.complete());
        assertEquals(93, first.fetchedCount() + second.fetchedCount() + last.fetchedCount());
        verify(historyStateStore).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq("page-3"));
        server.verify();
    }

    @Test
    void transientFullFetchFailurePreservesTheCurrentPageAndDoesNotCompleteIt() {
        when(historyStateStore.claim(USER_ID)).thenReturn(claimWithPageToken("BOOTSTRAP", "page-2"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess(messageListJson(List.of("pending-message"), "page-3"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/messages/pending-message?format=full")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "30")
                        .body("""
                                {"error":{"errors":[{"reason":"tooManyConcurrentRequests"}]}}
                                """));

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertTrue(response.waiting());
        assertFalse(response.complete());
        verify(historyStateStore, never()).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), any());
        verify(historyStateStore).defer(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(8));
        server.verify();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void malformedFullMessageDoesNotAdvanceTheScanOrSilentlyComplete(CapturedOutput output) {
        when(historyStateStore.claim(USER_ID)).thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withSuccess(messageListJson(List.of("malformed-message"), null), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/messages/malformed-message?format=full")))
                .andRespond(withSuccess("""
                        {"labelIds":["INBOX","CATEGORY_PERSONAL"],"payload":{"headers":[]}}
                        """, APPLICATION_JSON));
        when(emailRepository.existsByGmailConnectionIdAndGmailMessageId(
                CONNECTION_ID, "malformed-message")).thenReturn(false);

        GmailBackfillException exception = assertThrows(
                GmailBackfillException.class, () -> syncService.refresh(USER_ID));

        assertEquals("GMAIL_INVALID_RESPONSE", exception.getCode());
        verify(historyStateStore, never()).completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), any());
        verify(historyStateStore).release(eq(CONNECTION_ID), eq(LOCK_ID));
        assertTrue(output.getOut().contains("GMAIL_MESSAGE_FAILED event=GMAIL_MESSAGE_FAILED"));
        assertTrue(output.getOut().contains("SYNC_FAILED event=SYNC_FAILED connectionId=7"));
        server.verify();
    }

    @Test
    void aRateLimitedRequestIsPersistedForBackgroundRetryInsteadOfReturnedAsAnError() {
        when(historyStateStore.claim(USER_ID)).thenReturn(
                claim("BOOTSTRAP", null, null, List.of(), 0, null));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("""
                        {"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}
                        """));

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertTrue(response.waiting());
        assertTrue(response.hasMore());
        assertFalse(response.complete());
        assertTrue(response.retryAt().toInstant().isAfter(NOW));
        verify(historyStateStore).defer(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(8));
        verify(historyStateStore, never()).release(eq(CONNECTION_ID), eq(LOCK_ID));
        server.verify();
    }

    @Test
    void retriesAfterHonorsRetryAfterAndClassifiesConcurrent429AsTemporary() {
        when(historyStateStore.claim(USER_ID)).thenReturn(
                claim("BOOTSTRAP", null, null, List.of(), 0, null));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "30")
                        .body("""
                                {"error":{"errors":[{"reason":"tooManyConcurrentRequests"}]}}
                                """));

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertTrue(response.waiting());
        assertTrue(response.retryAt().toInstant().isAfter(NOW.plusSeconds(60 * 60 * 24)));
        verify(historyStateStore).defer(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(8));
        server.verify();
    }

    @Test
    void eighthTransientFailureStaysWaitingAndSchedulesCappedAutomaticContinuation() {
        org.mockito.stubbing.OngoingStubbing<GmailHistoryStateStore.Claim> claims =
                when(historyStateStore.claim(USER_ID));
        for (int attempt = 0; attempt < 8; attempt++) {
            claims = claims.thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null, attempt));
        }
        server.expect(org.springframework.test.web.client.ExpectedCount.times(8),
                        requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("""
                                {"error":{"errors":[{"reason":"rateLimitExceeded"}]}}
                                """));

        RefreshEmailsResponse response = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            response = syncService.refresh(USER_ID);
        }

        assertTrue(response != null && response.waiting());
        assertTrue(response.hasMore());
        assertFalse(response.complete());
        assertEquals("WAITING", response.phase());
        assertEquals(NOW.plusSeconds(900), response.retryAt().toInstant());
        verify(historyStateStore, org.mockito.Mockito.times(8))
                .defer(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(8));
        server.verify();
    }

    @Test
    void projectQuota403IsDeferredAndOAuth401RequiresReconnect() {
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null))
                .thenReturn(claim("BOOTSTRAP", null, null, List.of(), 0, null));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("""
                        {"error":{"errors":[{"reason":"quotaExceeded"}]}}
                        """));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        RefreshEmailsResponse projectQuota = syncService.refresh(USER_ID);
        assertTrue(projectQuota.waiting());
        verify(historyStateStore).defer(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(8));

        RefreshEmailsResponse oauthExpired = syncService.refresh(USER_ID);
        assertTrue(oauthExpired.reconnectRequired());
        assertFalse(oauthExpired.waiting());
        verify(historyStateStore).markAuthenticationRequired(CONNECTION_ID, LOCK_ID);
        server.verify();
    }

    @Test
    void anUnrelatedForbiddenResponseRequiresReauthenticationInsteadOfQuotaBackoff() {
        when(historyStateStore.claim(USER_ID)).thenReturn(
                claim("BOOTSTRAP", null, null, List.of(), 0, null));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("""
                        {"error":{"errors":[{"reason":"insufficientPermissions"}]}}
                        """));

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertTrue(response.reconnectRequired());
        assertFalse(response.waiting());
        verify(historyStateStore).markAuthenticationRequired(CONNECTION_ID, LOCK_ID);
        verify(historyStateStore, never()).defer(any(), any(), any(), any(Integer.class));
        server.verify();
    }

    private GmailHistoryStateStore.Claim claim(
            String phase,
            String cursor,
            String baseline,
            List<String> pending,
            int offset,
            String retryHistoryId) {
        return claim(phase, cursor, baseline, pending, offset, retryHistoryId, 0);
    }

    private GmailHistoryStateStore.Claim claim(
            String phase,
            String cursor,
            String baseline,
            List<String> pending,
            int offset,
            String retryHistoryId,
            int retryAttempts) {
        return new GmailHistoryStateStore.Claim(
                GmailHistoryStateStore.Status.CLAIMED,
                LOCK_ID,
                CONNECTION_ID,
                "owner@gmail.com",
                cursor,
                phase,
                baseline,
                CUTOFF,
                6,
                QUERY,
                null,
                null,
                retryHistoryId,
                pending,
                offset,
                null,
                retryAttempts,
                false,
                false);
    }

    private GmailHistoryStateStore.Claim claimWithPageToken(String phase, String pageToken) {
        return new GmailHistoryStateStore.Claim(
                GmailHistoryStateStore.Status.CLAIMED,
                LOCK_ID,
                CONNECTION_ID,
                "owner@gmail.com",
                null,
                phase,
                null,
                CUTOFF,
                6,
                QUERY,
                pageToken,
                null,
                null,
                List.of(),
                0,
                null,
                0,
                false,
                false);
    }

    private List<String> messageIds(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> "m" + index).toList();
    }

    private String messageListJson(List<String> ids, String nextToken) {
        String messages = ids.stream()
                .map(id -> "{\"id\":\"" + id + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"messages\":[" + messages + "]"
                + (nextToken == null ? "" : ",\"nextPageToken\":\"" + nextToken + "\"") + "}";
    }

    private void mockSpamMetadata(String id) {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages/" + id + "?format=full")))
                .andRespond(withSuccess("""
                        {"internalDate":"1792680000000","labelIds":["INBOX","CATEGORY_PERSONAL"],
                         "payload":{"headers":[{"name":"List-ID","value":"list.example"}]}}
                        """, APPLICATION_JSON));
        when(emailRepository.existsByGmailConnectionIdAndGmailMessageId(CONNECTION_ID, id)).thenReturn(false);
        when(emailService.categorizeMessage(any(), eq(id))).thenReturn(EmailCategory.SPAM);
        when(emailService.createEmailFromGmailResponse(
                eq(null), eq(id), any(), any(), eq(EmailCategory.SPAM))).thenAnswer(invocation -> {
            Email email = new Email();
            email.setGmailMessageId(id);
            email.setCategory(EmailCategory.SPAM);
            return email;
        });
    }
}
