package com.buildassist.repository;

import com.buildassist.model.Email;
import com.buildassist.model.EmailCategory;
import com.buildassist.model.EmailDraft;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmailSearchRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GmailConnectionRepository gmailConnectionRepository;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private EmailDraftRepository emailDraftRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private GmailConnection ownConnection;
    private OffsetDateTime sameReceivedAt;

    @BeforeEach
    void setUp() {
        User user = saveUser("owner@example.com");
        ownConnection = saveConnection(user, "owner@gmail.com");
        GmailConnection otherConnection = saveConnection(
                saveUser("other@example.com"), "other@gmail.com");

        sameReceivedAt = OffsetDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneOffset.UTC);
        Email quoteEmail = saveEmail(ownConnection, "100% Masonry offer", "quotes@example.com",
                sameReceivedAt, EmailCategory.QUOTE_REQUEST);
        Email otherEmail = saveEmail(ownConnection, "General question", "Builder@Example.com",
                sameReceivedAt, EmailCategory.OTHER);
        Email spamEmail = saveEmail(ownConnection, "Newsletter", "news@example.com",
                sameReceivedAt, EmailCategory.SPAM);
        Email reviewEmail = saveEmail(ownConnection, "Current review", "review@example.com",
                sameReceivedAt, EmailCategory.QUOTE_REQUEST);
        Email draftReviewEmail = saveEmail(ownConnection, "Draft review", "draft-review@example.com",
                sameReceivedAt, EmailCategory.QUOTE_REQUEST);
        saveEmail(otherConnection, "Masonry offer", "quotes@example.com",
                sameReceivedAt, EmailCategory.QUOTE_REQUEST);

        saveDraft(quoteEmail, EmailDraft.DraftStatus.DRAFT, Instant.parse("2026-10-20T10:00:00Z"));
        saveDraft(quoteEmail, EmailDraft.DraftStatus.READY, Instant.parse("2026-10-20T11:00:00Z"));
        saveDraft(spamEmail, EmailDraft.DraftStatus.DRAFT, Instant.parse("2026-10-20T10:00:00Z"));
        saveDraft(reviewEmail, EmailDraft.DraftStatus.PENDING_REVIEW, Instant.parse("2026-10-20T12:00:00Z"));
        saveDraft(draftReviewEmail, EmailDraft.DraftStatus.DRAFT, Instant.parse("2026-10-20T13:00:00Z"));
    }

    @Test
    void searchIsScopedByConnectionAndReturnsLatestDraftWithoutDuplicateEmails() {
        Page<EmailRepository.SearchRow> result = emailRepository.searchEmailsDescending(
                ownConnection.getId(), null, null, null, PageRequest.of(0, 25));

        assertEquals(5, result.getTotalElements());
        assertEquals(5, result.getContent().size());
        assertEquals(sameReceivedAt.toInstant(), result.getContent().getFirst().getReceivedAt());
        assertEquals("READY", result.getContent().stream()
                .filter(row -> row.getSubject().equals("100% Masonry offer"))
                .findFirst().orElseThrow().getDraftStatus());
        assertEquals(3L, emailRepository.countSearchEmailsByCategory(
                ownConnection.getId(), null).stream()
                .filter(count -> count.getCategory().equals("QUOTE_REQUEST"))
                .findFirst().orElseThrow().getCount());
        assertEquals(2L, emailRepository.countReviewEmails(ownConnection.getId(), null));
    }

    @Test
    void searchesSenderAndSubjectCaseInsensitivelyAndEscapesLikeWildcards() {
        Page<EmailRepository.SearchRow> subjectMatch = search("%masonry%", null);
        Page<EmailRepository.SearchRow> senderMatch = search("%builder@example.com%", null);
        Page<EmailRepository.SearchRow> literalWildcardMatch = search("%!%%", null);

        assertEquals(1, subjectMatch.getTotalElements());
        assertEquals(1, senderMatch.getTotalElements());
        assertEquals("General question", senderMatch.getContent().getFirst().getSubject());
        assertEquals(1, literalWildcardMatch.getTotalElements());
        assertEquals("100% Masonry offer", literalWildcardMatch.getContent().getFirst().getSubject());
    }

    @Test
    void combinesCategoryAndLatestDraftStatusFilters() {
        Page<EmailRepository.SearchRow> readyQuotes = emailRepository.searchEmailsDescending(
                ownConnection.getId(), null, "QUOTE_REQUEST", "READY", PageRequest.of(0, 25));
        Page<EmailRepository.SearchRow> oldDrafts = emailRepository.searchEmailsDescending(
                ownConnection.getId(), null, null, "DRAFT", PageRequest.of(0, 25));
        Page<EmailRepository.SearchRow> review = emailRepository.searchEmailsDescending(
                ownConnection.getId(), null, "QUOTE_REQUEST", "REVIEW", PageRequest.of(0, 25));

        assertEquals(1, readyQuotes.getTotalElements());
        assertEquals("100% Masonry offer", readyQuotes.getContent().getFirst().getSubject());
        assertEquals(2, oldDrafts.getTotalElements());
        assertEquals("Draft review", oldDrafts.getContent().getFirst().getSubject());
        assertEquals(2, review.getTotalElements());
        assertEquals("Draft review", review.getContent().getFirst().getSubject());
    }

    @Test
    void paginationAndEqualTimestampOrderingAreStableInBothDirections() {
        Page<EmailRepository.SearchRow> descendingFirstPage = emailRepository.searchEmailsDescending(
                ownConnection.getId(), null, null, null, PageRequest.of(0, 2));
        Page<EmailRepository.SearchRow> descendingSecondPage = emailRepository.searchEmailsDescending(
                ownConnection.getId(), null, null, null, PageRequest.of(1, 2));
        Page<EmailRepository.SearchRow> descendingThirdPage = emailRepository.searchEmailsDescending(
                ownConnection.getId(), null, null, null, PageRequest.of(2, 2));
        Page<EmailRepository.SearchRow> ascending = emailRepository.searchEmailsAscending(
                ownConnection.getId(), null, null, null, PageRequest.of(0, 25));

        assertEquals(5, descendingFirstPage.getTotalElements());
        assertEquals(2, descendingFirstPage.getContent().size());
        assertEquals(3, descendingFirstPage.getTotalPages());
        assertEquals(2, descendingSecondPage.getContent().size());
        assertEquals(descendingFirstPage.getContent().get(0).getId() - 1,
                descendingFirstPage.getContent().get(1).getId());
        assertEquals(descendingFirstPage.getContent().get(1).getId() - 1,
                descendingSecondPage.getContent().getFirst().getId());
        assertEquals(descendingThirdPage.getContent().getFirst().getId(),
                ascending.getContent().getFirst().getId());
    }

    @Test
    void tabCountsFollowSearchButIgnoreActiveCategoryAndDraftFilters() {
        List<EmailRepository.CategoryCount> counts =
                emailRepository.countSearchEmailsByCategory(ownConnection.getId(), "%example.com%");

        assertEquals(1L, counts.stream()
                .filter(count -> count.getCategory().equals("SPAM"))
                .findFirst().orElseThrow().getCount());
        assertEquals(3L, counts.stream()
                .filter(count -> count.getCategory().equals("QUOTE_REQUEST"))
                .findFirst().orElseThrow().getCount());
        assertEquals(2L, emailRepository.countReviewEmails(ownConnection.getId(), "%example.com%"));
    }

    @Test
    void coverageDecisionsSurviveDatabaseReloadAndRemainAttachedToTheirDraft() {
        Email email = saveEmail(ownConnection, "Coverage persistence", "coverage@example.com",
                sameReceivedAt, EmailCategory.QUOTE_REQUEST);
        EmailDraft original = new EmailDraft();
        original.setEmail(email);
        original.setDraftBody("Saved quote");
        original.setLineItemsJson("[{\"name\":\"Sitt elszállítása\",\"unit\":\"m3\","
                + "\"quantity\":1,\"unitPrice\":0,\"subtotal\":0,\"lineItemId\":\"line-1\","
                + "\"catalogItemId\":null}]");
        original.setRequestedWorkItemsJson("[{\"workId\":\"work-1\",\"name\":\"Sitt elszállítása\","
                + "\"explanation\":\"Kért munka.\",\"source\":\"EMAIL\",\"quantity\":null,"
                + "\"unit\":null,\"suggestedCatalogItemId\":null,\"suggestedCatalogItemName\":null,"
                + "\"suggestedCalculationStrategy\":null}]");
        original.setCoverageDecisionsJson("[{\"issueId\":\"work-1\",\"status\":\"INCLUDED\","
                + "\"lineItemId\":\"line-1\",\"lineItemFingerprint\":\"fingerprint\","
                + "\"userConfirmed\":true}]");
        original.setAcknowledgedWarningsJson("[\"warning-accepted-zero-price\"]");
        original.setAnalysisFingerprint("0123456789abcdef");
        EmailDraft savedOriginal = emailDraftRepository.saveAndFlush(original);
        Long originalId = savedOriginal.getId();

        EmailDraft reanalysis = new EmailDraft();
        reanalysis.setEmail(email);
        reanalysis.setDraftBody("");
        EmailDraft savedReanalysis = emailDraftRepository.saveAndFlush(reanalysis);

        entityManager.clear();
        EmailDraft restored = emailDraftRepository.findById(originalId).orElseThrow();

        assertEquals("[{\"issueId\":\"work-1\",\"status\":\"INCLUDED\",\"lineItemId\":\"line-1\","
                + "\"lineItemFingerprint\":\"fingerprint\",\"userConfirmed\":true}]",
                restored.getCoverageDecisionsJson());
        assertEquals("[\"warning-accepted-zero-price\"]", restored.getAcknowledgedWarningsJson());
        assertEquals("0123456789abcdef", restored.getAnalysisFingerprint());
        assertEquals("Sitt elszállítása", restored.getLineItems().getFirst().name());
        assertEquals(originalId, savedOriginal.getId());
        assertTrue(savedReanalysis.getId() > originalId);
    }

    private Page<EmailRepository.SearchRow> search(String pattern, String category) {
        return emailRepository.searchEmailsDescending(
                ownConnection.getId(), pattern, category, null, PageRequest.of(0, 25));
    }

    private User saveUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("test-hash");
        user.setActive(true);
        return userRepository.save(user);
    }

    private GmailConnection saveConnection(User user, String address) {
        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        connection.setGmailAddress(address);
        connection.setAccessTokenEncrypted("access");
        connection.setRefreshTokenEncrypted("refresh");
        connection.setTokenExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));
        return gmailConnectionRepository.save(connection);
    }

    private Email saveEmail(
            GmailConnection connection,
            String subject,
            String sender,
            OffsetDateTime receivedAt,
            EmailCategory category) {
        Email email = new Email();
        email.setGmailConnection(connection);
        email.setGmailMessageId(subject + sender);
        email.setSubject(subject);
        email.setFromAddress(sender);
        email.setBodyText("Body is intentionally not part of search.");
        email.setEmailReceivedAt(receivedAt);
        email.setSyncedAt(receivedAt);
        email.setCategory(category);
        return emailRepository.save(email);
    }

    private void saveDraft(Email email, EmailDraft.DraftStatus status, Instant createdAt) {
        EmailDraft draft = new EmailDraft();
        draft.setEmail(email);
        draft.setDraftBody("Draft");
        draft.setStatus(status);
        draft.setCreatedAt(createdAt);
        emailDraftRepository.save(draft);
    }
}
