package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.dto.EmailDtos.RefreshEmailsResponse;
import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.repository.EmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

class GmailHistorySyncTest {

    private static final Long USER_ID = 10L;
    private static final Long CONNECTION_ID = 20L;
    private static final UUID LOCK_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T18:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-04-22T18:00:00Z");

    private GmailBackfillStateStore backfillStateStore;
    private GmailHistoryStateStore historyStateStore;
    private GmailTokenProvider gmailService;
    private EmailService emailService;
    private EmailRepository emailRepository;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GmailInboxSyncService syncService;
    private int[] quotaUnitsInWindow;

    @BeforeEach
    void setUp() {
        backfillStateStore = mock(GmailBackfillStateStore.class);
        historyStateStore = mock(GmailHistoryStateStore.class);
        gmailService = mock(GmailTokenProvider.class);
        emailService = mock(EmailService.class);
        emailRepository = mock(EmailRepository.class);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        AppProperties properties = new AppProperties();
        quotaUnitsInWindow = new int[] {0};
        GmailSyncWindow window = new GmailSyncWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        syncService = new GmailInboxSyncService(
                historyStateStore,
                gmailService,
                emailService,
                emailRepository,
                window,
                new GmailApiClient((connectionId, operation) -> {
                    int cost = operation.quotaUnits(properties.getGmail());
                    if (quotaUnitsInWindow[0] + cost > 4_800) {
                        throw new GmailRateLimitDeferredException(NOW.plusSeconds(60), "quota");
                    }
                    quotaUnitsInWindow[0] += cost;
                    return () -> { };
                }, restTemplate, new ObjectMapper()),
                new ObjectMapper(),
                new GmailRetryPolicy(properties),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(gmailService.accessToken(CONNECTION_ID)).thenReturn("access-token");
    }

    @Test
    void bootstrapPinsHistoryBeforeListingAndImportsOnlyDatesInsideExactBoundary() {
        when(historyStateStore.claim(USER_ID)).thenReturn(claim(null, null, null, null, null, List.of(), 0));
        server.expect(requestTo("https://www.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"historyId\":\"h-start\"}", APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/messages?"),
                        org.hamcrest.Matchers.containsString("after:"))))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"messages":[{"id":"before"},{"id":"exact"},{"id":"after"}]}
                        """, APPLICATION_JSON));

        mockMetadata("before", CUTOFF.minusMillis(1), true);
        mockMetadata("exact", CUTOFF, true);
        mockMetadata("after", CUTOFF.plusMillis(1), true);
        stubSpamEmail("exact");
        stubSpamEmail("after");

        when(historyStateStore.completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(null))).thenReturn(2);

        RefreshEmailsResponse result = syncService.refresh(USER_ID);

        assertEquals(2, result.fetchedCount());
        assertEquals(3, result.processedCount());
        assertFalse(result.hasMore());
        assertTrue(result.complete());
        assertEquals("INITIAL_COMPLETE", result.phase());
        org.mockito.ArgumentCaptor<String> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(historyStateStore).beginScan(
                eq(CONNECTION_ID), eq(LOCK_ID), eq("h-start"), eq(CUTOFF), queryCaptor.capture(), eq(false));
        assertEquals(
                "label:INBOX category:primary -category:promotions -category:social "
                        + "-category:updates -in:spam -in:trash after:1776880799",
                queryCaptor.getValue());
        server.verify();
    }

    @Test
    void processesMoreThanFiftyUniqueHistoryIdsInResumableBatchesAndDeduplicatesEvents() {
        List<String> ids = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "m" + i).toList();
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, List.of(), 0))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, ids, 50, "h2"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/history?")))
                .andExpect(method(GET))
                .andRespond(withSuccess(historyResponse(ids), APPLICATION_JSON));
        ids.forEach(id -> {
            mockMetadata(id, NOW, true);
            stubSpamEmail(id);
        });
        when(historyStateStore.completeHistoryBatch(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(50))).thenReturn(50);

        RefreshEmailsResponse first = syncService.refresh(USER_ID);

        assertEquals(50, first.processedCount());
        assertEquals(50, first.fetchedCount());
        assertTrue(first.hasMore());
        assertFalse(first.complete());
        org.mockito.ArgumentCaptor<List<String>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(historyStateStore).stageHistoryPage(
                eq(CONNECTION_ID), eq(LOCK_ID), captor.capture(), eq(null), eq("h2"));
        assertEquals(51, captor.getValue().size());
        assertEquals(ids, captor.getValue());
        when(historyStateStore.completeHistoryBatch(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(1))).thenReturn(1);
        RefreshEmailsResponse second = syncService.refresh(USER_ID);
        assertEquals(1, second.processedCount());
        assertEquals(1, second.fetchedCount());
        assertTrue(second.complete());
        verify(emailRepository, org.mockito.Mockito.times(51))
                .existsByGmailConnectionIdAndGmailMessageId(eq(CONNECTION_ID), anyString());
        verify(historyStateStore).stageHistoryPage(
                eq(CONNECTION_ID), eq(LOCK_ID), any(), any(), any());
        server.verify();
    }

    @Test
    void processesTwoHundredMessagesInQuotaBoundedResumableBatches() {
        List<String> ids = java.util.stream.IntStream.range(0, 200).mapToObj(i -> "bulk-" + i).toList();
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, ids, 0, "h2"))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, ids, 50, "h2"))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, ids, 100, "h2"))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, ids, 150, "h2"));
        ids.forEach(id -> {
            mockMetadata(id, NOW, true);
            stubSpamEmail(id);
        });
        when(historyStateStore.completeHistoryBatch(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(50)))
                .thenReturn(50);

        RefreshEmailsResponse first = syncService.refresh(USER_ID);
        assertEquals(3_000, quotaUnitsInWindow[0]);
        quotaUnitsInWindow[0] = 0;
        RefreshEmailsResponse second = syncService.refresh(USER_ID);
        assertEquals(3_000, quotaUnitsInWindow[0]);
        quotaUnitsInWindow[0] = 0;
        RefreshEmailsResponse third = syncService.refresh(USER_ID);
        assertEquals(3_000, quotaUnitsInWindow[0]);
        quotaUnitsInWindow[0] = 0;
        RefreshEmailsResponse last = syncService.refresh(USER_ID);
        assertEquals(3_000, quotaUnitsInWindow[0]);

        assertEquals(50, first.processedCount());
        assertEquals(50, second.processedCount());
        assertEquals(50, third.processedCount());
        assertEquals(50, last.processedCount());
        assertFalse(last.hasMore());
        assertTrue(last.complete());
        verify(emailRepository, org.mockito.Mockito.times(200))
                .existsByGmailConnectionIdAndGmailMessageId(eq(CONNECTION_ID), anyString());
        server.verify();
    }

    @Test
    void expiredHistoryCursorStartsSixMonthRecoveryAndKeepsNewArrivalsCatchable() {
        when(historyStateStore.claim(USER_ID)).thenReturn(
                claim("expired", "HISTORY", null, CUTOFF, null, List.of(), 0));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/history?")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("https://www.googleapis.com/gmail/v1/users/me/profile"))
                .andRespond(withSuccess("{\"historyId\":\"h-recovery\"}", APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/messages?"),
                        org.hamcrest.Matchers.containsString("after:"))))
                .andRespond(withSuccess("{\"messages\":[]}", APPLICATION_JSON));
        when(historyStateStore.completeScanPage(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(null))).thenReturn(0);

        RefreshEmailsResponse result = syncService.refresh(USER_ID);

        assertFalse(result.hasMore());
        assertTrue(result.complete());
        assertEquals("INITIAL_COMPLETE", result.phase());
        verify(historyStateStore).beginScan(
                eq(CONNECTION_ID), eq(LOCK_ID), eq("h-recovery"), eq(CUTOFF), anyString(), eq(true));
        server.verify();
    }

    @Test
    void historyLabelEventsFetchOnlyUnknownMessagesAndRequireCurrentPrimaryLabels() {
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, List.of(), 0));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode history = root.putArray("history");
        ObjectNode event = history.addObject();
        ArrayNode labelChanges = event.putArray("labelsRemoved");
        ObjectNode change = labelChanges.addObject();
        change.putObject("message").put("id", "old-primary");
        ObjectNode added = history.addObject();
        added.putArray("labelsAdded").addObject().putObject("message").put("id", "not-primary");
        root.put("historyId", "h2");
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/history?")))
                .andRespond(withSuccess(root.toString(), APPLICATION_JSON));
        mockMetadata("old-primary", CUTOFF.minusMillis(1), true);
        mockMetadata("not-primary", CUTOFF.plusSeconds(20), false);
        when(historyStateStore.completeHistoryBatch(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(2))).thenReturn(0);

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertTrue(response.complete());
        assertEquals(2, response.processedCount());
        verify(emailRepository).existsByGmailConnectionIdAndGmailMessageId(CONNECTION_ID, "old-primary");
        verify(emailRepository).existsByGmailConnectionIdAndGmailMessageId(CONNECTION_ID, "not-primary");
        server.verify();
    }

    @Test
    void fullMessageFetchKeepsAiBodyAndDoesNotRefetchStoredHistoryMessages() {
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null,
                        List.of("stored-message", "new-message"), 0, "h2"));
        when(emailRepository.existsByGmailConnectionIdAndGmailMessageId(CONNECTION_ID, "stored-message"))
                .thenReturn(true);
        when(emailRepository.existsByGmailConnectionIdAndGmailMessageId(CONNECTION_ID, "new-message"))
                .thenReturn(false);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/messages/new-message?format=full")))
                .andRespond(withSuccess("""
                        {"internalDate":"1792680000000","labelIds":["INBOX","CATEGORY_PERSONAL"],
                         "payload":{"headers":[{"name":"Subject","value":"Árajánlat"},
                           {"name":"From","value":"customer@example.com"}],
                           "body":{"data":"cXVvdGUtcmVxdWVzdC1ib2R5"}}}
                        """, APPLICATION_JSON));
        when(emailService.createEmailFromGmailResponse(
                eq(null), eq("new-message"), any(), any(), eq(EmailCategory.OTHER)))
                .thenAnswer(invocation -> {
                    Email email = new Email();
                    email.setGmailMessageId("new-message");
                    email.setCategory(EmailCategory.OTHER);
                    email.setBodyText("quote-request-body");
                    return email;
                });
        when(historyStateStore.completeHistoryBatch(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(2)))
                .thenReturn(1);

        RefreshEmailsResponse response = syncService.refresh(USER_ID);

        assertEquals(1, response.fetchedCount());
        assertTrue(response.complete());
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> fullMessage =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(emailService).createEmailFromGmailResponse(
                eq(null), eq("new-message"), any(), fullMessage.capture(), eq(EmailCategory.OTHER));
        assertEquals("cXVvdGUtcmVxdWVzdC1ib2R5",
                fullMessage.getValue().path("payload").path("body").path("data").asText());
        server.verify();
    }

    @Test
    void completesEveryHistoryPageBeforeAdvancingTheCommittedCursor() {
        when(historyStateStore.claim(USER_ID))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, null, List.of(), 0))
                .thenReturn(claim("h1", "HISTORY", null, CUTOFF, "history-page-2", List.of(), 0));
        when(emailRepository.existsByGmailConnectionIdAndGmailMessageId(anyLong(), anyString())).thenReturn(true);
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/history?"),
                        org.hamcrest.Matchers.containsString("startHistoryId=h1"))))
                .andRespond(withSuccess("""
                        {"history":[{"messagesAdded":[{"message":{"id":"page-one"}}]}],
                         "nextPageToken":"history-page-2","historyId":"h1-page"}
                        """, APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/history?"),
                        org.hamcrest.Matchers.containsString("pageToken=history-page-2"),
                        org.hamcrest.Matchers.containsString("startHistoryId=h1"))))
                .andRespond(withSuccess("""
                        {"history":[{"labelsAdded":[{"message":{"id":"page-two"}}]}],"historyId":"h2"}
                        """, APPLICATION_JSON));
        when(historyStateStore.completeHistoryBatch(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(1))).thenReturn(0);

        RefreshEmailsResponse first = syncService.refresh(USER_ID);

        assertTrue(first.hasMore());
        assertFalse(first.complete());
        when(historyStateStore.completeHistoryBatch(eq(CONNECTION_ID), eq(LOCK_ID), any(), eq(1))).thenReturn(0);

        RefreshEmailsResponse second = syncService.refresh(USER_ID);

        assertTrue(second.complete());
        assertFalse(second.hasMore());
        verify(historyStateStore).stageHistoryPage(
                eq(CONNECTION_ID), eq(LOCK_ID), eq(List.of("page-one")), eq("history-page-2"), eq("h1-page"));
        verify(historyStateStore).stageHistoryPage(
                eq(CONNECTION_ID), eq(LOCK_ID), eq(List.of("page-two")), eq(null), eq("h2"));
        server.verify();
    }

    private void mockMetadata(String id, Instant receivedAt, boolean primary) {
        List<String> labels = primary
                ? List.of("INBOX", "CATEGORY_PERSONAL")
                : List.of("CATEGORY_PERSONAL");
        String labelJson = labels.stream()
                .map(label -> "\"" + label + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages/" + id + "?format=full")))
                .andRespond(withSuccess("""
                        {"internalDate":"%d","labelIds":[%s],"payload":{"headers":[
                          {"name":"Subject","value":"Newsletter"},
                          {"name":"From","value":"news@example.com"},
                          {"name":"List-ID","value":"news.example.com"}
                        ],"body":{"data":"c3VwZXJmbHVvdXMtdGVzdC1ib2R5"}}}
                        """.formatted(receivedAt.toEpochMilli(), labelJson), APPLICATION_JSON));
    }

    private void stubSpamEmail(String id) {
        when(emailService.createEmailFromGmailResponse(
                eq(null), eq(id), any(), any(), eq(EmailCategory.OTHER))).thenAnswer(invocation -> {
                    Email email = new Email();
                    email.setGmailMessageId(id);
                    email.setSubject("Newsletter");
                    email.setFromAddress("news@example.com");
                    email.setBodyText("superfluous-test-body");
                    email.setCategory(EmailCategory.OTHER);
                    email.setEmailReceivedAt(java.time.OffsetDateTime.ofInstant(
                            Instant.ofEpochMilli(invocation.getArgument(3, com.fasterxml.jackson.databind.JsonNode.class)
                                    .path("internalDate").asLong()),
                            ZoneOffset.UTC));
                    return email;
                });
    }

    private String historyResponse(List<String> ids) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode history = root.putArray("history");
        for (String id : ids) {
            ObjectNode event = history.addObject();
            event.putArray("messagesAdded").addObject().putObject("message").put("id", id);
        }
        history.addObject().putArray("labelsAdded").addObject().putObject("message").put("id", ids.get(0));
        root.put("historyId", "h2");
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private GmailHistoryStateStore.Claim claim(
            String cursor,
            String phase,
            String baseline,
            Instant cutoff,
            String pageToken,
            List<String> pending,
            int offset) {
        return claim(cursor, phase, baseline, cutoff, pageToken, pending, offset, null);
    }

    private GmailHistoryStateStore.Claim claim(
            String cursor,
            String phase,
            String baseline,
            Instant cutoff,
            String pageToken,
            List<String> pending,
            int offset,
            String pendingHistoryId) {
        return new GmailHistoryStateStore.Claim(
                GmailHistoryStateStore.Status.CLAIMED,
                LOCK_ID,
                CONNECTION_ID,
                "owner@gmail.com",
                cursor,
                phase,
                baseline,
                cutoff,
                cutoff == null ? null : 6,
                cutoff == null ? null : new GmailSyncWindow(
                        new AppProperties(), Clock.fixed(NOW, ZoneOffset.UTC)).queryFor(cutoff),
                pageToken,
                null,
                pendingHistoryId,
                pending,
                offset);
    }

}
