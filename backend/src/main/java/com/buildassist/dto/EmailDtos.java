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
            EmailDraft.DraftStatus draftStatus,
            String classificationStatus,
            String classificationReason,
            String classificationEvidence) {
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
            String classificationStatus,
            String classificationReason,
            String classificationEvidence,
            DraftDtos.GenerateDraftResponse draftEmail) {
    }

    public record RefreshEmailsResponse(
            int fetchedCount,
            int processedCount,
            boolean hasMore,
            boolean complete,
            boolean inProgress,
            String phase,
            String message,
            boolean waiting,
            OffsetDateTime retryAt,
            boolean reconnectRequired) {
        public RefreshEmailsResponse(
                int fetchedCount,
                int processedCount,
                boolean hasMore,
                boolean complete,
                boolean inProgress,
                String phase,
                String message) {
            this(fetchedCount, processedCount, hasMore, complete, inProgress, phase, message, false, null, false);
        }

        public RefreshEmailsResponse(int fetchedCount) {
            this(fetchedCount, fetchedCount, false, true, false, "COMPLETE", "Frissítés befejeződött.", false, null, false);
        }
    }

    public record BackfillResponse(
            int importedCount,
            int processedCount,
            boolean hasMore,
            boolean complete,
            boolean inProgress,
            String message) {
    }

    public record UpdateCategoryRequest(EmailCategory category) {
    }

    public record UpdateCategoryResponse(Long id, EmailCategory category) {
    }

    public record ReclassificationResponse(int queued) {
    }
}
