package com.buildassist.dto;

import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.DraftQuoteSnapshot;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

public final class DraftDtos {

    private DraftDtos() {
    }

    public record GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                        List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                        String clientName, String additionalNotes, boolean includeAdditionalNotes,
                                        boolean vatRegistered, List<AiDtos.CoverageWorkItem> coverageItems,
                                        List<AiDtos.CoverageWarning> coverageWarnings, boolean analysisFresh,
                                        boolean gmailDraftCreated, DraftQuoteSnapshot quoteSnapshot,
                                        CalculationStatus calculationStatus, BigDecimal netQuotePrice) {
        public GenerateDraftResponse(
                Long draftId, String draftBody, List<String> unmappedRequests,
                List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                String clientName, String additionalNotes, boolean includeAdditionalNotes,
                boolean vatRegistered, List<AiDtos.CoverageWorkItem> coverageItems,
                List<AiDtos.CoverageWarning> coverageWarnings, boolean analysisFresh,
                boolean gmailDraftCreated) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName,
                    additionalNotes, includeAdditionalNotes, vatRegistered, coverageItems,
                    coverageWarnings, analysisFresh, gmailDraftCreated, null, null, null);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, null, true, false,
                    List.of(), List.of(), true, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName, String additionalNotes) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, additionalNotes, true, false,
                    List.of(), List.of(), true, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName, String additionalNotes, boolean includeAdditionalNotes) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, additionalNotes,
                    includeAdditionalNotes, false, List.of(), List.of(), true, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                    List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, null, null, true, false,
                    List.of(), List.of(), true, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName, String additionalNotes, boolean includeAdditionalNotes,
                                     boolean vatRegistered) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, additionalNotes,
                    includeAdditionalNotes, vatRegistered, List.of(), List.of(), true, false);
        }

        public GenerateDraftResponse(Long draftId, String draftBody, List<String> unmappedRequests,
                                     List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                                     String clientName, String additionalNotes, boolean includeAdditionalNotes,
                                     boolean vatRegistered, List<AiDtos.CoverageWorkItem> coverageItems,
                                     List<AiDtos.CoverageWarning> coverageWarnings, boolean analysisFresh) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName, additionalNotes,
                    includeAdditionalNotes, vatRegistered, coverageItems, coverageWarnings, analysisFresh, false);
        }
    }

    public record AnalysisResponse(Long draftId, String clientName, List<AiDtos.DraftLineItem> lineItems,
                                   List<String> unmappedRequests, List<String> reviewWarnings,
                                   String additionalNotes, boolean includeAdditionalNotes, boolean vatRegistered,
                                   List<AiDtos.CoverageWorkItem> coverageItems,
                                   List<AiDtos.CoverageWarning> coverageWarnings, boolean analysisFresh,
                                   DraftQuoteSnapshot quoteSnapshot, CalculationStatus calculationStatus,
                                   BigDecimal netQuotePrice) {
        public AnalysisResponse(
                Long draftId, String clientName, List<AiDtos.DraftLineItem> lineItems,
                List<String> unmappedRequests, List<String> reviewWarnings, String additionalNotes,
                boolean includeAdditionalNotes, boolean vatRegistered,
                List<AiDtos.CoverageWorkItem> coverageItems,
                List<AiDtos.CoverageWarning> coverageWarnings, boolean analysisFresh) {
            this(draftId, clientName, lineItems, unmappedRequests, reviewWarnings, additionalNotes,
                    includeAdditionalNotes, vatRegistered, coverageItems, coverageWarnings,
                    analysisFresh, null, null, null);
        }
    }

    public record AnalyzeRequest(String additionalNotes) {
    }

    public record FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                       String additionalNotes, Boolean includeAdditionalNotes,
                                       Boolean confirmIncomplete, String confirmedFingerprint) {
        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems) {
            this(clientName, lineItems, null, null, false, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes) {
            this(clientName, lineItems, additionalNotes, null, false, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes, Boolean includeAdditionalNotes) {
            this(clientName, lineItems, additionalNotes, includeAdditionalNotes, false, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes, Boolean includeAdditionalNotes,
                                    Boolean confirmIncomplete) {
            this(clientName, lineItems, additionalNotes, includeAdditionalNotes, confirmIncomplete, null);
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
