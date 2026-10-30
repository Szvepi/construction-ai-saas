package com.buildassist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

public final class AiQuoteDtos {

    private AiQuoteDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StructuredExtraction(
            @JsonProperty("client_name") String clientName,
            @JsonProperty("work_items") List<WorkProposal> workItems,
            @JsonProperty("unmapped_requests") List<UnmappedRequest> unmappedRequests,
            @JsonProperty("review_warnings") List<String> reviewWarnings) {
        public StructuredExtraction {
            workItems = workItems == null ? List.of() : List.copyOf(workItems);
            unmappedRequests = unmappedRequests == null ? List.of() : List.copyOf(unmappedRequests);
            reviewWarnings = reviewWarnings == null ? List.of() : List.copyOf(reviewWarnings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkProposal(
            @JsonProperty("work_ref") String workRef,
            @JsonProperty("description") String description,
            @JsonProperty("explicitly_requested") Boolean explicitlyRequested,
            @JsonProperty("source") String source,
            @JsonProperty("source_evidence") String sourceEvidence,
            @JsonProperty("requested_quantity") BigDecimal requestedQuantity,
            @JsonProperty("requested_unit") String requestedUnit,
            @JsonProperty("settlement_mode") String settlementMode,
            @JsonProperty("settlement_evidence") String settlementEvidence,
            @JsonProperty("explanation") String explanation,
            @JsonProperty("proposed_lines") List<LineProposal> proposedLines,
            @JsonProperty("missing_parameters") List<MissingParameter> missingParameters,
            @JsonProperty("assumptions") List<String> assumptions) {
        public WorkProposal {
            proposedLines = proposedLines == null ? List.of() : List.copyOf(proposedLines);
            missingParameters = missingParameters == null ? List.of() : List.copyOf(missingParameters);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineProposal(
            @JsonProperty("line_ref") String lineRef,
            @JsonProperty("catalog_item_id") Long catalogItemId,
            @JsonProperty("calculation_plan") PlanProposal calculationPlan,
            @JsonProperty("procurement_output_reference") String procurementOutputReference,
            @JsonProperty("procurement_package_count_reference") String procurementPackageCountReference,
            @JsonProperty("explanation") String explanation,
            @JsonProperty("assumptions") List<String> assumptions) {
        public LineProposal {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanProposal(
            @JsonProperty("inputs") List<InputProposal> inputs,
            @JsonProperty("steps") List<StepProposal> steps,
            @JsonProperty("output_reference") String outputReference,
            @JsonProperty("review_reasons") List<String> reviewReasons) {
        public PlanProposal {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            steps = steps == null ? List.of() : List.copyOf(steps);
            reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputProposal(
            @JsonProperty("id") String id,
            @JsonProperty("value") BigDecimal value,
            @JsonProperty("unit") String unit,
            @JsonProperty("source") String source,
            @JsonProperty("evidence") String evidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepProposal(
            @JsonProperty("id") String id,
            @JsonProperty("operation") String operation,
            @JsonProperty("operands") List<String> operands,
            @JsonProperty("output_unit") String outputUnit,
            @JsonProperty("scale") Integer scale,
            @JsonProperty("rounding_mode") String roundingMode) {
        public StepProposal {
            operands = operands == null ? null : List.copyOf(operands);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MissingParameter(
            @JsonProperty("id") String id,
            @JsonProperty("unit") String unit,
            @JsonProperty("reason") String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnmappedRequest(
            @JsonProperty("work_ref") String workRef,
            @JsonProperty("request") String request,
            @JsonProperty("source") String source,
            @JsonProperty("source_evidence") String sourceEvidence,
            @JsonProperty("reason") String reason) {
    }
}
