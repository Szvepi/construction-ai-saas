package com.buildassist.dto;

import com.buildassist.model.EmailCategory;
import com.buildassist.model.EmailDraft;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class EmailDtos {

    private EmailDtos() {
    }

    public record EmailSummaryResponse(
            Long id,
            String subject,
            String fromAddress,
            OffsetDateTime receivedAt,
            boolean replied,
            EmailCategory category,
            EmailDraft.DraftStatus draftStatus) {
    }

    public record EmailSearchResponse(
            List<EmailSummaryResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            Map<EmailCategory, Long> categoryCounts,
            long reviewCount) {
    }

    public record EmailDetailResponse(
            Long id,
            String subject,
            String fromAddress,
            String bodyText,
            OffsetDateTime receivedAt,
            boolean replied,
            EmailCategory category,
            DraftDtos.GenerateDraftResponse draftEmail) {
    }

    public record RefreshEmailsResponse(int fetchedCount) {
    }

    public record UpdateCategoryRequest(EmailCategory category) {
    }

    public record UpdateCategoryResponse(Long id, EmailCategory category) {
    }
}
