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
                                        CalculationStatus calculationStatus, BigDecimal netQuotePrice,
                                        String taxRegime, BigDecimal vatRate, BigDecimal vatAmount,
                                        BigDecimal grossQuotePrice, boolean taxConfigurationValid,
                                        long draftVersion) {
        public GenerateDraftResponse(
                Long draftId, String draftBody, List<String> unmappedRequests,
                List<String> reviewWarnings, List<AiDtos.DraftLineItem> lineItems,
                String clientName, String additionalNotes, boolean includeAdditionalNotes,
                boolean vatRegistered, List<AiDtos.CoverageWorkItem> coverageItems,
                List<AiDtos.CoverageWarning> coverageWarnings, boolean analysisFresh,
                boolean gmailDraftCreated, DraftQuoteSnapshot quoteSnapshot,
                CalculationStatus calculationStatus, BigDecimal netQuotePrice) {
            this(draftId, draftBody, unmappedRequests, reviewWarnings, lineItems, clientName,
                    additionalNotes, includeAdditionalNotes, vatRegistered, coverageItems,
                    coverageWarnings, analysisFresh, gmailDraftCreated, quoteSnapshot,
                    calculationStatus, netQuotePrice, vatRegistered ? "VAT_REGISTERED" : "AAM",
                    null, null, null, !vatRegistered, 0);
        }

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
                                   BigDecimal netQuotePrice, String taxRegime, BigDecimal vatRate,
                                   BigDecimal vatAmount, BigDecimal grossQuotePrice,
                                   boolean taxConfigurationValid, long draftVersion) {
        public AnalysisResponse(
                Long draftId, String clientName, List<AiDtos.DraftLineItem> lineItems,
                List<String> unmappedRequests, List<String> reviewWarnings, String additionalNotes,
                boolean includeAdditionalNotes, boolean vatRegistered,
                List<AiDtos.CoverageWorkItem> coverageItems,
                List<AiDtos.CoverageWarning> coverageWarnings, boolean analysisFresh,
                DraftQuoteSnapshot quoteSnapshot, CalculationStatus calculationStatus,
                BigDecimal netQuotePrice) {
            this(draftId, clientName, lineItems, unmappedRequests, reviewWarnings, additionalNotes,
                    includeAdditionalNotes, vatRegistered, coverageItems, coverageWarnings,
                    analysisFresh, quoteSnapshot, calculationStatus, netQuotePrice,
                    vatRegistered ? "VAT_REGISTERED" : "AAM", null, null, null,
                    !vatRegistered, 0);
        }
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
                                       Boolean confirmIncomplete, String confirmedFingerprint,
                                       Long expectedVersion) {
        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems) {
            this(clientName, lineItems, null, null, false, null, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes) {
            this(clientName, lineItems, additionalNotes, null, false, null, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes, Boolean includeAdditionalNotes) {
            this(clientName, lineItems, additionalNotes, includeAdditionalNotes, false, null, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes, Boolean includeAdditionalNotes,
                                    Boolean confirmIncomplete) {
            this(clientName, lineItems, additionalNotes, includeAdditionalNotes, confirmIncomplete, null, null);
        }

        public FinalizeDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                    String additionalNotes, Boolean includeAdditionalNotes,
                                    Boolean confirmIncomplete, String confirmedFingerprint) {
            this(clientName, lineItems, additionalNotes, includeAdditionalNotes,
                    confirmIncomplete, confirmedFingerprint, null);
        }
    }

    public record SaveDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                   String additionalNotes, Boolean includeAdditionalNotes,
                                   Long expectedVersion) {
        public SaveDraftRequest(String clientName, List<AiDtos.DraftLineItem> lineItems,
                                String additionalNotes) {
            this(clientName, lineItems, additionalNotes, null, null);
        }

        public SaveDraftRequest(
                String clientName, List<AiDtos.DraftLineItem> lineItems,
                String additionalNotes, Boolean includeAdditionalNotes) {
            this(clientName, lineItems, additionalNotes, includeAdditionalNotes, null);
        }
    }

    public record AddCatalogLineRequest(
            String workId, Long catalogItemId, BigDecimal quantity, String unit, Long expectedVersion,
            String unmappedId, List<MappingParameterValue> parameters) {
        public AddCatalogLineRequest {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        public AddCatalogLineRequest(
                String workId, Long catalogItemId, BigDecimal quantity, String unit, Long expectedVersion) {
            this(workId, catalogItemId, quantity, unit, expectedVersion, null, List.of());
        }

        public AddCatalogLineRequest(
                String workId, Long catalogItemId, BigDecimal quantity, String unit,
                Long expectedVersion, String unmappedId) {
            this(workId, catalogItemId, quantity, unit, expectedVersion, unmappedId, List.of());
        }
    }

    public record MappingParameterValue(String id, BigDecimal value, String unit) {
        public MappingParameterValue(String id, BigDecimal value) {
            this(id, value, null);
        }
    }

    public record MappingParameterDescriptor(
            String id, String label, String unit, String displayUnit, BigDecimal value,
            BigDecimal displayValue, boolean required,
            String source, boolean editable) {
    }

    public record WorkMappingPreviewResponse(
            String workId, String unmappedId, Long catalogItemId, String catalogItemName,
            String outputUnit, List<MappingParameterDescriptor> parameters,
            com.buildassist.calculation.CalculationStatus calculationStatus,
            List<com.buildassist.calculation.CalculationIssue> issues) {
        public WorkMappingPreviewResponse {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record AddManualLineRequest(
            String name, BigDecimal quantity, String unit, BigDecimal netUnitPrice,
            com.buildassist.calculation.CatalogItemType itemType, String workId, Long expectedVersion) {
    }

    public record UpdateManualLineRequest(
            String name, BigDecimal quantity, String unit, BigDecimal netUnitPrice,
            com.buildassist.calculation.CatalogItemType itemType, Long expectedVersion) {
    }

    public record UpdateDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftRequest(@NotBlank String draftBody) {
    }

    public record SendDraftResponse(boolean sent) {
    }
}
