package com.buildassist.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class DraftDtos {

    private DraftDtos() {
    }

    public record GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                        List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                        String clientName, String additionalNotes, boolean includeAdditionalNotes,
                                        boolean vatRegistered) {
        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, null, true, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName, String additionalNotes) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, additionalNotes, true, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName, String additionalNotes, boolean includeAdditionalNotes) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, additionalNotes,
                    includeAdditionalNotes, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                    List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, null, null, true, false);
        }
    }

    public record AnalysisResponse(Long draftId, String clientName, List<AiDtos.DraftLineItem> lineItems,
                                   List<String> unmappedRequests, List<String> reviewWarnings,
                                   String additionalNotes, boolean includeAdditionalNotes, boolean vatRegistered) {
    }

    public record AnalyzeRequest(String additionalNotes) {
    }

    public record FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                       String additionalNotes, Boolean includeAdditionalNotes) {
        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems) {
            this(clientName, lineItems, null, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes) {
            this(clientName, lineItems, additionalNotes, null);
        }
    }

    public record SaveDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                   String additionalNotes, Boolean includeAdditionalNotes) {
        public SaveDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                String additionalNotes) {
            this(clientName, lineItems, additionalNotes, null);
        }
    }

    public record UpdateDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftResponse(boolean sent) {
    }
}
