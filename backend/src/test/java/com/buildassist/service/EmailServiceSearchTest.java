package com.buildassist.service;

import com.buildassist.model.EmailCategory;
import com.buildassist.model.Email;
import com.buildassist.model.EmailDraft;
import com.buildassist.model.GmailConnection;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.EmailDraftRepository;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceSearchTest {

    @Mock
    private EmailRepository emailRepository;
    @Mock
    private EmailDraftRepository emailDraftRepository;
    @Mock
    private GmailConnectionRepository gmailConnectionRepository;
    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private GmailService gmailService;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(
                emailRepository,
                emailDraftRepository,
                gmailConnectionRepository,
                gmailService,
                companyProfileRepository);
    }

    @Test
    void searchUsesAuthenticatedUsersConnectionAndEscapesSearchWildcards() {
        GmailConnection connection = new GmailConnection();
        connection.setId(77L);
        when(gmailConnectionRepository.findByUserId(5L)).thenReturn(Optional.of(connection));
        when(emailRepository.searchEmailsAscending(eq(77L), eq("%a!!b!%!_%"), eq("QUOTE_REQUEST"),
                eq("PENDING_REVIEW"), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 25)));
        when(emailRepository.countSearchEmailsByCategory(77L, "%a!!b!%!_%", "PENDING_REVIEW"))
                .thenReturn(List.of());

        var response = emailService.searchEmails(
                5L, " A!B%_ ", EmailCategory.QUOTE_REQUEST,
                EmailDraft.DraftStatus.PENDING_REVIEW, 0, 25, "ASC");

        assertEquals(0, response.totalElements());
        assertEquals(0, response.categoryCounts().get(EmailCategory.QUOTE_REQUEST));
        assertEquals(3, response.categoryCounts().size());
        verify(gmailConnectionRepository).findByUserId(5L);
        verify(emailRepository).searchEmailsAscending(eq(77L), any(), any(), any(), any());
        verify(emailDraftRepository, never()).findTopByEmailIdOrderByCreatedAtDesc(any());
    }

    @Test
    void noGmailConnectionReturnsAnEmptyPageWithoutRunningEmailQueries() {
        when(gmailConnectionRepository.findByUserId(8L)).thenReturn(Optional.empty());

        var response = emailService.searchEmails(8L, null, null, null, 2, 25, "desc");

        assertEquals(2, response.page());
        assertEquals(25, response.size());
        assertEquals(0, response.totalElements());
        verify(emailRepository, never()).searchEmailsAscending(any(), any(), any(), any(), any());
        verify(emailRepository, never()).searchEmailsDescending(any(), any(), any(), any(), any());
        verify(emailRepository, never()).countSearchEmailsByCategory(any(), any(), any());
    }

    @Test
    void rejectsOutOfRangePaginationAndUnsupportedDirection() {
        assertThrows(IllegalArgumentException.class,
                () -> emailService.searchEmails(1L, null, null, null, -1, 25, "desc"));
        assertThrows(IllegalArgumentException.class,
                () -> emailService.searchEmails(1L, null, null, null, 0, 101, "desc"));
        assertThrows(IllegalArgumentException.class,
                () -> emailService.searchEmails(1L, null, null, null, 0, 25, "receivedAt"));
    }

    @Test
    void legacyListApiStillReturnsItsExistingSummaryShape() {
        GmailConnection connection = new GmailConnection();
        connection.setId(77L);
        Email email = new Email();
        email.setId(12L);
        email.setSubject("Legacy inbox item");
        email.setFromAddress("sender@example.com");
        email.setEmailReceivedAt(OffsetDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneOffset.UTC));
        email.setCategory(EmailCategory.QUOTE_REQUEST);
        EmailDraft draft = new EmailDraft();
        draft.setStatus(EmailDraft.DraftStatus.PENDING_REVIEW);
        when(gmailConnectionRepository.findByUserId(5L)).thenReturn(Optional.of(connection));
        when(emailRepository.findByGmailConnectionIdOrderByEmailReceivedAtDesc(77L))
                .thenReturn(List.of(email));
        when(emailDraftRepository.findTopByEmailIdOrderByCreatedAtDesc(12L))
                .thenReturn(Optional.of(draft));

        var response = emailService.listEmails(5L);

        assertEquals(1, response.size());
        assertEquals(12L, response.getFirst().id());
        assertEquals("Legacy inbox item", response.getFirst().subject());
        assertEquals(EmailCategory.QUOTE_REQUEST, response.getFirst().category());
        assertEquals(EmailDraft.DraftStatus.PENDING_REVIEW, response.getFirst().draftStatus());
    }
}
