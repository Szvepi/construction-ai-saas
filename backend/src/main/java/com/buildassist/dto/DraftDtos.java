package com.buildassist.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class DraftDtos {

    private DraftDtos() {
    }

    public record GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests) {
    }

    public record UpdateDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftResponse(boolean sent) {
    }
}
