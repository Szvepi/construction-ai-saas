package com.buildassist.dto;

import com.buildassist.model.EmailCategory;
import java.time.OffsetDateTime;

public final class EmailDtos {

    private EmailDtos() {
    }

    public record EmailSummaryResponse(
            Long id,
            String subject,
            String fromAddress,
            OffsetDateTime receivedAt,
            boolean replied,
            EmailCategory category) {
    }

    public record EmailDetailResponse(
            Long id,
            String subject,
            String fromAddress,
            String bodyText,
            OffsetDateTime receivedAt,
            boolean replied,
            EmailCategory category) {
    }

    public record RefreshEmailsResponse(int fetchedCount) {
    }

    public record UpdateCategoryRequest(EmailCategory category) {
    }

    public record UpdateCategoryResponse(Long id, EmailCategory category) {
    }
}
