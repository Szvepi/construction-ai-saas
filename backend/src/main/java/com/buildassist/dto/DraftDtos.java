package com.buildassist.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class DraftDtos {

    private DraftDtos() {
    }

    public record GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                        List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                        String clientName) {
        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                    List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, null);
        }
    }

    public record AnalysisResponse(Long draftId, String clientName, List<AiDtos.DraftLineItem> lineItems,
                                   List<String> unmappedRequests, List<String> reviewWarnings) {
    }

    public record FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems) {
    }

    public record SaveDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems) {
    }

    public record UpdateDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftResponse(boolean sent) {
    }
}
