package com.buildassist.service;

import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.WorkRecognitionStatus;
import com.buildassist.dto.AiDtos.CoverageDecision;
import com.buildassist.dto.AiDtos.CoverageResponse;
import com.buildassist.dto.AiDtos.CoverageStatus;
import com.buildassist.dto.AiDtos.CoverageWarning;
import com.buildassist.dto.AiDtos.CoverageWorkItem;
import com.buildassist.dto.AiDtos.CoverageSource;
import com.buildassist.model.EmailDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class QuoteCoverageService {

    private final ObjectMapper objectMapper;

    public QuoteCoverageService(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public CoverageResponse response(
            DraftQuoteSnapshot snapshot,
            List<CoverageDecision> decisions,
            List<String> warnings,
            List<String> acknowledgedWarningIds,
            boolean analysisFresh,
            String quoteFingerprint) {
        List<CoverageWorkItem> workItems = evaluate(snapshot, decisions);
        Set<String> acknowledged = new HashSet<>(
                acknowledgedWarningIds == null ? List.of() : acknowledgedWarningIds);
        List<CoverageWarning> warningItems = warningItems(warnings, acknowledged);
        List<String> unresolved = new ArrayList<>();
        for (CoverageWorkItem work : workItems) {
            if (work.status() == CoverageStatus.MISSING) {
                unresolved.add(work.name() + " – nincs megfelelő katalógustétel");
            } else if (work.status() == CoverageStatus.NEEDS_REVIEW) {
                unresolved.add(work.name() + " – ellenőrizendő (" + work.calculationStatus() + ")");
            }
        }
        duplicateChargeWarnings(snapshot, decisions).forEach(unresolved::add);
        warningItems.stream()
                .filter(warning -> !warning.acknowledged())
                .map(CoverageWarning::text)
                .forEach(unresolved::add);
        if (!analysisFresh) {
            unresolved.add("Az e-mail vagy a kiegészítő információ megváltozott; futtass új elemzést.");
        }
        return new CoverageResponse(
                workItems, warningItems, analysisFresh, List.copyOf(unresolved), quoteFingerprint);
    }

    public List<CoverageWorkItem> evaluate(
            DraftQuoteSnapshot snapshot, List<CoverageDecision> decisions) {
        if (snapshot == null) {
            return List.of();
        }
        Map<String, CoverageDecision> decisionByWork = new HashMap<>();
        if (decisions != null) {
            for (CoverageDecision decision : decisions) {
                if (decision != null && decision.issueId() != null) {
                    decisionByWork.put(decision.issueId(), decision);
                }
            }
        }

        return snapshot.workItems().stream()
                .map(work -> evaluateWork(snapshot, work, decisionByWork.get(work.workId())))
                .toList();
    }

    public List<CoverageDecision> setDecision(
            List<CoverageDecision> existing,
            String workId,
            CoverageStatus status,
            String lineItemId,
            DraftQuoteSnapshot snapshot) {
        if (workId == null || workId.isBlank() || status == null || snapshot == null) {
            throw new IllegalArgumentException("A work identifier, decision and quote snapshot are required.");
        }
        DraftQuoteSnapshot.QuoteWorkSnapshot work = snapshot.workItems().stream()
                .filter(item -> workId.equals(item.workId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The selected work item does not exist."));

        String fingerprint = null;
        if (status == CoverageStatus.INCLUDED) {
            DraftQuoteSnapshot.QuoteLineSnapshot line = snapshot.lineItems().stream()
                    .filter(item -> workId.equals(item.workId()))
                    .filter(DraftQuoteSnapshot.QuoteLineSnapshot::chargeable)
                    .filter(item -> item.calculationResult().status() == CalculationStatus.VALID)
                    .filter(item -> Objects.equals(lineItemId, item.lineItemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "An included work must reference one of its valid, directly linked quote lines."));
            fingerprint = lineItemFingerprint(snapshot, line);
        } else if (status != CoverageStatus.EXCLUDED
                && status != CoverageStatus.NEEDS_REVIEW
                && status != CoverageStatus.MISSING) {
            throw new IllegalArgumentException("The work coverage status is invalid.");
        }

        List<CoverageDecision> updated = new ArrayList<>(existing == null ? List.of() : existing);
        updated.removeIf(decision -> workId.equals(decision.issueId()));
        updated.add(new CoverageDecision(workId, status, lineItemId, fingerprint, true));
        return List.copyOf(updated);
    }

    public String lineItemFingerprint(
            DraftQuoteSnapshot snapshot, DraftQuoteSnapshot.QuoteLineSnapshot line) {
        return sha256(String.join("|",
                Objects.toString(line == null ? null : line.lineItemId(), ""),
                Objects.toString(line == null ? null : line.workId(), ""),
                Objects.toString(line == null ? null : line.catalogItemId(), ""),
                Objects.toString(line == null || line.calculationResult() == null
                        ? null : line.calculationResult().status(), ""),
                decimal(line == null ? null : line.customerChargeQuantity()),
                decimal(line == null ? null : line.netLineTotal()),
                decimal(line == null ? null : line.manualNetUnitPrice()),
                Objects.toString(line == null ? null : line.manualPriceReason(), ""),
                Objects.toString(line == null ? null : line.calculationPlan(), ""),
                Objects.toString(line == null ? null : line.catalogItem(), ""),
                Objects.toString(line == null ? null : line.requirements(), ""),
                Boolean.toString(line != null && line.chargeable())));
    }

    public String analysisFingerprint(String emailBody, String additionalNotes) {
        return sha256(Objects.toString(emailBody, "") + "\n--MIRA-ADDITIONAL-NOTES--\n"
                + Objects.toString(additionalNotes, "").trim());
    }

    public String quoteFingerprint(EmailDraft draft, DraftQuoteSnapshot snapshot) {
        List<String> decisions = readDecisions(draft.getCoverageDecisionsJson()).stream()
                .sorted(Comparator.comparing(CoverageDecision::issueId))
                .map(decision -> String.join("|", decision.issueId(), decision.status().name(),
                        Objects.toString(decision.lineItemId(), ""),
                        Objects.toString(decision.lineItemFingerprint(), ""),
                        Boolean.toString(decision.userConfirmed())))
                .toList();
        List<String> acknowledged = readStringList(draft.getAcknowledgedWarningsJson()).stream()
                .sorted().toList();
        List<String> warnings = readStringList(draft.getReviewWarningsJson()).stream()
                .map(this::warningId).sorted().toList();
        return sha256(String.join("\n--MIRA-FINGERPRINT-SECTION--\n",
                Objects.toString(snapshot == null ? null : snapshot.fingerprint(), ""),
                Objects.toString(draft.getClientName(), ""),
                Objects.toString(draft.getAdditionalNotes(), ""),
                Boolean.toString(draft.isIncludeAdditionalNotes()),
                String.join("\n", decisions),
                String.join("\n", acknowledged),
                String.join("\n", warnings)));
    }

    public List<CoverageWarning> warningItems(List<String> warnings, Set<String> acknowledgedIds) {
        if (warnings == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        Set<String> acknowledged = acknowledgedIds == null ? Set.of() : acknowledgedIds;
        List<CoverageWarning> results = new ArrayList<>();
        for (String warning : warnings) {
            if (warning == null || warning.isBlank()) {
                continue;
            }
            String id = warningId(warning);
            if (seen.add(id)) {
                results.add(new CoverageWarning(id, warning.trim(), acknowledged.contains(id)));
            }
        }
        return List.copyOf(results);
    }

    public String warningId(String warning) {
        return "warning-" + sha256(normalize(warning));
    }

    public List<CoverageDecision> readDecisions(String json) {
        return readList(json, CoverageDecision.class);
    }

    public List<String> readStringList(String json) {
        return readList(json, String.class);
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize quote review data.", exception);
        }
    }

    private CoverageWorkItem evaluateWork(
            DraftQuoteSnapshot snapshot,
            DraftQuoteSnapshot.QuoteWorkSnapshot work,
            CoverageDecision decision) {
        List<DraftQuoteSnapshot.QuoteLineSnapshot> relatedLines = snapshot.lineItems().stream()
                .filter(line -> work.workId().equals(line.workId()))
                .toList();
        List<String> linkedIds = relatedLines.stream()
                .filter(DraftQuoteSnapshot.QuoteLineSnapshot::chargeable)
                .filter(line -> line.calculationResult().status() == CalculationStatus.VALID)
                .map(DraftQuoteSnapshot.QuoteLineSnapshot::lineItemId)
                .toList();

        CoverageStatus status = decisionStatus(snapshot, work, relatedLines, decision, linkedIds);
        if ((decision == null || decision.status() != CoverageStatus.EXCLUDED)
                && duplicateChargeWorkIds(snapshot, Set.of()).contains(work.workId())) {
            status = CoverageStatus.NEEDS_REVIEW;
        }
        CalculationStatus calculationStatus = combinedCalculationStatus(relatedLines);
        String linkedLineId = linkedIds.isEmpty() ? null : linkedIds.getFirst();
        String explanation = work.explanation();
        if (explanation == null || explanation.isBlank()) {
            explanation = work.originalRequest();
        }

        if (!work.unmappedLines().isEmpty()) {
            String unmappedDetails = work.unmappedLines().stream()
                    .map(line -> line.explanation() == null || line.explanation().isBlank()
                            ? line.reason() : line.explanation() + " (" + line.reason() + ")")
                    .collect(Collectors.joining("; "));
            explanation = explanation + " Nem lefedett tételjavaslat: " + unmappedDetails;
        }
        return new CoverageWorkItem(
                work.workId(),
                work.originalRequest(),
                explanation,
                coverageSource(work.source()),
                work.customerRequestedQuantity() == null ? null
                        : work.customerRequestedQuantity().value(),
                work.customerRequestedQuantity() == null ? work.customerRequestedUnit()
                        : work.customerRequestedQuantity().unit().code(),
                status,
                linkedLineId,
                decision != null && decision.userConfirmed(),
                linkedIds,
                work.recognitionStatus(),
                calculationStatus);
    }

    public Set<String> duplicateChargeWorkIds(DraftQuoteSnapshot snapshot, Set<String> excludedWorkIds) {
        if (snapshot == null) {
            return Set.of();
        }
        Set<String> excluded = excludedWorkIds == null ? Set.of() : excludedWorkIds;
        Set<String> duplicateWorkIds = new HashSet<>();
        Map<String, List<DraftQuoteSnapshot.QuoteLineSnapshot>> byWork =
                snapshot.lineItems().stream()
                        .filter(DraftQuoteSnapshot.QuoteLineSnapshot::chargeable)
                        .filter(line -> line.calculationResult() != null
                                && line.calculationResult().status() == CalculationStatus.VALID)
                        .filter(line -> !excluded.contains(line.workId()))
                        .collect(Collectors.groupingBy(DraftQuoteSnapshot.QuoteLineSnapshot::workId));
        byWork.forEach((workId, lines) -> {
            for (int first = 0; first < lines.size(); first++) {
                for (int second = first + 1; second < lines.size(); second++) {
                    if (isCombinedComponentDoubleCharge(lines.get(first), lines.get(second))) {
                        duplicateWorkIds.add(workId);
                    }
                }
            }
        });
        return Set.copyOf(duplicateWorkIds);
    }

    public List<String> duplicateChargeWarnings(
            DraftQuoteSnapshot snapshot, List<CoverageDecision> decisions) {
        Set<String> excluded = decisions == null ? Set.of() : decisions.stream()
                .filter(Objects::nonNull)
                .filter(decision -> decision.status() == CoverageStatus.EXCLUDED)
                .map(CoverageDecision::issueId)
                .collect(Collectors.toSet());
        return duplicateChargeWorkIds(snapshot, excluded).stream()
                .map(workId -> "Egy kombinált ajánlati sor és egy külön anyag-/munkadíjsor ugyanazt a mennyiséget számolja el. "
                        + "Ellenőrizd a(z) " + workId + " munkafázist.")
                .toList();
    }

    private boolean isCombinedComponentDoubleCharge(
            DraftQuoteSnapshot.QuoteLineSnapshot first,
            DraftQuoteSnapshot.QuoteLineSnapshot second) {
        DraftQuoteSnapshot.CatalogItemSnapshot firstItem = first.catalogItem();
        DraftQuoteSnapshot.CatalogItemSnapshot secondItem = second.catalogItem();
        if (firstItem == null || secondItem == null
                || firstItem.itemType() == secondItem.itemType()
                || (firstItem.itemType() != com.buildassist.calculation.CatalogItemType.COMBINED
                && secondItem.itemType() != com.buildassist.calculation.CatalogItemType.COMBINED)) {
            return false;
        }
        if (first.customerChargeQuantity() == null || second.customerChargeQuantity() == null) {
            return false;
        }
        Set<com.buildassist.calculation.ChargeComponent> firstComponents = first.requirements().stream()
                .map(DraftQuoteSnapshot.RequirementSnapshot::component)
                .collect(Collectors.toSet());
        boolean overlaps = second.requirements().stream()
                .map(DraftQuoteSnapshot.RequirementSnapshot::component)
                .anyMatch(firstComponents::contains);
        if (!overlaps) {
            return false;
        }
        try {
            return first.customerChargeQuantity().convertTo(second.customerChargeQuantity().unit())
                    .value().compareTo(second.customerChargeQuantity().value()) == 0;
        } catch (IllegalArgumentException incompatibleUnits) {
            return false;
        }
    }

    private CoverageStatus decisionStatus(
            DraftQuoteSnapshot snapshot,
            DraftQuoteSnapshot.QuoteWorkSnapshot work,
            List<DraftQuoteSnapshot.QuoteLineSnapshot> relatedLines,
            CoverageDecision decision,
            List<String> linkedIds) {
        if (decision != null) {
            if (decision.status() == CoverageStatus.EXCLUDED) {
                return CoverageStatus.EXCLUDED;
            }
            if (decision.status() == CoverageStatus.MISSING
                    || decision.status() == CoverageStatus.NEEDS_REVIEW) {
                return decision.status();
            }
            if (decision.status() == CoverageStatus.INCLUDED) {
                if (work.recognitionStatus() != WorkRecognitionStatus.MAPPED
                        || relatedLines.stream().anyMatch(line ->
                        line.calculationResult().status() != CalculationStatus.VALID)
                        || linkedIds.isEmpty()) {
                    return CoverageStatus.NEEDS_REVIEW;
                }
                DraftQuoteSnapshot.QuoteLineSnapshot selected = relatedLines.stream()
                        .filter(line -> Objects.equals(line.lineItemId(), decision.lineItemId()))
                        .findFirst().orElse(null);
                if (selected != null && linkedIds.contains(selected.lineItemId())
                        && Objects.equals(decision.lineItemFingerprint(),
                        lineItemFingerprint(snapshot, selected))) {
                    return CoverageStatus.INCLUDED;
                }
                return CoverageStatus.NEEDS_REVIEW;
            }
        }
        if (work.recognitionStatus() == WorkRecognitionStatus.UNMAPPED || relatedLines.isEmpty()) {
            return CoverageStatus.MISSING;
        }
        if (work.recognitionStatus() != WorkRecognitionStatus.MAPPED) {
            return CoverageStatus.NEEDS_REVIEW;
        }
        if (linkedIds.isEmpty()) {
            return CoverageStatus.NEEDS_REVIEW;
        }
        if (relatedLines.stream().anyMatch(line ->
                line.calculationResult().status() != CalculationStatus.VALID)) {
            return CoverageStatus.NEEDS_REVIEW;
        }
        return CoverageStatus.INCLUDED;
    }

    private CalculationStatus combinedCalculationStatus(
            List<DraftQuoteSnapshot.QuoteLineSnapshot> lines) {
        if (lines.stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.INVALID)) {
            return CalculationStatus.INVALID;
        }
        if (lines.stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.NEEDS_INPUT)) {
            return CalculationStatus.NEEDS_INPUT;
        }
        if (lines.stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.NEEDS_REVIEW)) {
            return CalculationStatus.NEEDS_REVIEW;
        }
        return lines.isEmpty() ? null : CalculationStatus.VALID;
    }

    private CoverageSource coverageSource(com.buildassist.calculation.InputSource source) {
        if (source == com.buildassist.calculation.InputSource.CUSTOMER_EMAIL) {
            return CoverageSource.EMAIL;
        }
        if (source == com.buildassist.calculation.InputSource.CUSTOMER_SUPPLEMENT) {
            return CoverageSource.ADDITIONAL_INFO;
        }
        return CoverageSource.UNKNOWN;
    }

    private <T> List<T> readList(String json, Class<T> itemType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, itemType));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored quote review data is invalid.", exception);
        }
    }

    private String decimal(com.buildassist.calculation.Quantity quantity) {
        if (quantity == null) {
            return "";
        }
        return quantity.value().stripTrailingZeros().toPlainString() + " " + quantity.unit().code();
    }

    private String decimal(java.math.BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
