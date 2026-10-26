package com.buildassist.service;

import com.buildassist.dto.AiDtos;
import com.buildassist.dto.DraftDtos;
import com.buildassist.model.Email;
import com.buildassist.model.EmailDraft;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.EmailDraftRepository;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDraftServiceCoverageTest {

    private static final Long DRAFT_ID = 81L;
    private static final Long USER_ID = 17L;

    @Mock private ChatModel chatModel;
    @Mock private EmailRepository emailRepository;
    @Mock private CatalogItemRepository catalogItemRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private GmailConnectionRepository gmailConnectionRepository;
    @Mock private EmailDraftRepository emailDraftRepository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private SpringTemplateEngine templateEngine;
    @Mock private QuotePdfService quotePdfService;
    @Mock private QuoteEmailMessageFactory quoteEmailMessageFactory;

    private QuoteCoverageService coverageService;
    private AiDraftService aiDraftService;

    @BeforeEach
    void setUp() {
        coverageService = new QuoteCoverageService(new ObjectMapper());
        aiDraftService = new AiDraftService(chatModel, emailRepository, catalogItemRepository,
                companyProfileRepository, gmailConnectionRepository, emailDraftRepository,
                tokenEncryptionService, templateEngine, quotePdfService, quoteEmailMessageFactory,
                coverageService);
    }

    @Test
    void savesUserDecisionAgainstSpecificLineAndRestoresItFromDraftData() {
        EmailDraft draft = draft(List.of(line("Sitt elszállítása", "line-1", null)));
        draft.setRequestedWorkItemsJson(coverageService.writeJson(List.of(work("work-1", null))));
        prepareDraft(draft);
        when(emailDraftRepository.save(any(EmailDraft.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(companyProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        DraftDtos.GenerateDraftResponse response = aiDraftService.updateCoverageDecision(
                DRAFT_ID, "work-1",
                new AiDraftsDecision(AiDtos.CoverageStatus.INCLUDED, "line-1",
                        saveRequest(List.of(line("Sitt elszállítása", "line-1", null)))).request(),
                USER_ID);

        assertEquals(AiDtos.CoverageStatus.INCLUDED, response.coverageItems().get(0).status());
        assertEquals("line-1", response.coverageItems().get(0).linkedLineItemId());
        assertTrue(response.coverageItems().get(0).userConfirmed());
        assertEquals(AiDtos.CoverageStatus.INCLUDED,
                coverageService.readDecisions(draft.getCoverageDecisionsJson()).get(0).status());
    }

    @Test
    void refusesCoverageReadAcrossUserTenants() {
        EmailDraft draft = draft(List.of());
        prepareDraft(draft);

        assertThrows(IllegalArgumentException.class, () -> aiDraftService.getCoverage(DRAFT_ID, USER_ID + 1));
    }

    @Test
    void refusesGmailDraftCreationUntilUnresolvedCoverageIsConfirmed() {
        EmailDraft draft = draft(List.of());
        draft.setDraftBody("previous preview");
        draft.setRequestedWorkItemsJson(coverageService.writeJson(List.of(work("work-1", 900L))));
        prepareDraft(draft);
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(emailDraftRepository.save(any(EmailDraft.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(templateEngine.process(any(String.class), any())).thenReturn("updated preview");

        DraftDtos.FinalizeDraftRequest request = new DraftDtos.FinalizeDraftRequest(
                "Ügyfél", List.of(), null, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> aiDraftService.finalizeDraft(DRAFT_ID, request, USER_ID));
        DraftDtos.FinalizeDraftRequest staleConfirmation = new DraftDtos.FinalizeDraftRequest(
                "Ügyfél", List.of(), null, true, true, "not-the-current-fingerprint");
        assertThrows(IllegalArgumentException.class,
                () -> aiDraftService.finalizeDraft(DRAFT_ID, staleConfirmation, USER_ID));

        verify(quotePdfService, never()).render(any(String.class));
        verify(gmailConnectionRepository, never()).findByUserId(any());
    }

    @Test
    void repeatedFinalizeDoesNotCreateAnotherGmailDraft() {
        EmailDraft draft = draft(List.of());
        draft.setDraftBody("previous preview");
        draft.setStatus(EmailDraft.DraftStatus.READY);
        when(emailDraftRepository.findByIdForUpdate(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(companyProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        DraftDtos.GenerateDraftResponse response = aiDraftService.finalizeDraft(
                DRAFT_ID, new DraftDtos.FinalizeDraftRequest("Ügyfél", List.of()), USER_ID);

        assertTrue(response.gmailDraftCreated());
        verify(quotePdfService, never()).render(any(String.class));
        verify(gmailConnectionRepository, never()).findByUserId(any());
    }

    private void prepareDraft(EmailDraft draft) {
        when(emailDraftRepository.findById(DRAFT_ID)).thenReturn(Optional.of(draft));
    }

    private EmailDraft draft(List<AiDtos.DraftLineItem> lineItems) {
        EmailDraft draft = new EmailDraft();
        draft.setId(DRAFT_ID);
        draft.setDraftBody("");
        draft.setLineItemsJson(coverageService.writeJson(lineItems));
        draft.setReviewWarningsJson("[]");
        draft.setUnmappedRequestsJson("[]");
        draft.setCoverageDecisionsJson("[]");
        draft.setAcknowledgedWarningsJson("[]");

        User user = new User();
        user.setId(USER_ID);
        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        Email email = new Email();
        email.setGmailConnection(connection);
        draft.setEmail(email);
        return draft;
    }

    private AiDtos.RequestedWork work(String id, Long catalogItemId) {
        return new AiDtos.RequestedWork(id, "Sitt elszállítása", "Ellenőrizendő munka.",
                AiDtos.CoverageSource.EMAIL, null, null, catalogItemId, null, null);
    }

    private AiDtos.DraftLineItem line(String name, String lineItemId, Long catalogItemId) {
        return new AiDtos.DraftLineItem(name, "db", BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, lineItemId, catalogItemId);
    }

    private DraftDtos.SaveDraftRequest saveRequest(List<AiDtos.DraftLineItem> lineItems) {
        return new DraftDtos.SaveDraftRequest("Ügyfél", lineItems, null, true);
    }

    private record AiDraftsDecision(AiDtos.CoverageStatus status, String lineItemId,
                                    DraftDtos.SaveDraftRequest draft) {
        AiDtos.CoverageDecisionRequest request() {
            return new AiDtos.CoverageDecisionRequest(status, lineItemId, draft);
        }
    }
}
