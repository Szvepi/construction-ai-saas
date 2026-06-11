package com.buildassist.dto;

import java.time.Instant;

public final class EmailDtos {

    private EmailDtos() {
    }

    public record EmailSummaryResponse(
        Long id,
        String subject,
        String fromAddress,
        Instant receivedAt,
        boolean replied) {
    }

    public record EmailDetailResponse(
        Long id,
        String subject,
        String fromAddress,
        String bodyText,
        Instant receivedAt,
        boolean replied) {
    }

    public record RefreshEmailsResponse(int fetchedCount) {
    }
}
