package com.buildassist.service;

import com.buildassist.dto.AiDtos.*;
import com.buildassist.model.CalculationStrategy;
import com.buildassist.model.CatalogItem;
import com.buildassist.model.EmailDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class QuoteCoverageService {

    private final ObjectMapper objectMapper;

    public QuoteCoverageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RequestedWork> createRequestedWorks(
            AiExtractionResult extraction, List<CatalogItem> catalogItems) {
        Map<Long, CatalogItem> catalogById = catalogItems.stream()
                .collect(Collectors.toMap(CatalogItem::getId, item -> item, (left, right) -> left));
        List<RequestedWork> works = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (extraction.requestedWorks() != null) {
            for (AiRequestedWork work : extraction.requestedWorks()) {
                if (work == null || work.name() == null || work.name().isBlank()) {
                    continue;
                }
                Long catalogId = catalogById.containsKey(work.catalogItemId()) ? work.catalogItemId() : null;
                CatalogItem catalogItem = catalogId == null ? null : catalogById.get(catalogId);
                CoverageSource source = parseSource(work.source());
                addWork(works, seen, work.name(), work.reason(), source, work.quantity(), work.unit(),
                        catalogId, catalogItem == null ? null : catalogItem.getName(),
                        catalogItem == null || catalogItem.getCalculationStrategy() == null
                                ? null : catalogItem.getCalculationStrategy().name());
            }
        }

        if (works.isEmpty() && extraction.items() != null) {
            for (AiExtractedItem item : extraction.items()) {
                CatalogItem catalogItem = catalogById.get(item.catalogItemId());
                if (catalogItem != null) {
                    addWork(works, seen, catalogItem.getName(),
                            "A korábbi AI-elemzés katalógustételként ismerte fel; az eredeti kérés forrása nem különíthető el.",
                            CoverageSource.UNKNOWN, item.quantity(), catalogItem.getUnit(),
                            catalogItem.getId(), catalogItem.getName(),
                            catalogItem.getCalculationStrategy() == null
                                    ? null : catalogItem.getCalculationStrategy().name());
                }
            }
        }

        if (extraction.unmappedRequests() != null) {
            for (String name : extraction.unmappedRequests()) {
                String normalizedName = normalize(name);
                if (name != null && !name.isBlank()
                        && seen.stream().noneMatch(key -> key.startsWith(normalizedName + "|"))) {
                    addWork(works, seen, name, "Mira nem talált hozzá egyértelmű árjegyzéki tételt.",
                            CoverageSource.UNKNOWN, null, null, null, null, null);
                }
            }
        }
        return List.copyOf(works);
    }

    public List<CoverageWorkItem> evaluate(
            List<RequestedWork> requestedWorks,
            List<CoverageDecision> decisions,
            List<DraftLineItem> lineItems) {
        Map<String, CoverageDecision> decisionByWorkId = new HashMap<>();
        decisions.forEach(decision -> decisionByWorkId.put(decision.issueId(), decision));
        List<DraftLineItem> safeLineItems = lineItems == null ? List.of() : lineItems;
        List<CoverageWorkItem> results = new ArrayList<>();

        for (RequestedWork work : requestedWorks) {
            CoverageDecision decision = decisionByWorkId.get(work.workId());
            if (decision != null) {
                if (decision.status() == CoverageStatus.EXCLUDED) {
                    results.add(toView(work, CoverageStatus.EXCLUDED, null, decision.userConfirmed()));
                    continue;
                }
                if (decision.status() == CoverageStatus.NEEDS_REVIEW || decision.status() == CoverageStatus.MISSING) {
                    results.add(toView(work, decision.status(), null, decision.userConfirmed()));
                    continue;
                }
                DraftLineItem linkedItem = findLineItem(safeLineItems, decision.lineItemId());
                if (linkedItem != null && Objects.equals(
                        decision.lineItemFingerprint(), lineItemFingerprint(linkedItem))) {
                    results.add(toView(work, CoverageStatus.INCLUDED, linkedItem.lineItemId(),
                            decision.userConfirmed()));
                } else {
                    results.add(toView(work, CoverageStatus.NEEDS_REVIEW, null, false));
                }
                continue;
            }

            long sameCatalogRequestCount = work.suggestedCatalogItemId() == null ? 0
                    : requestedWorks.stream()
                    .filter(candidate -> Objects.equals(
                            candidate.suggestedCatalogItemId(), work.suggestedCatalogItemId()))
                    .count();
            DraftLineItem matchingItem = findAutomaticMatch(work, safeLineItems, sameCatalogRequestCount > 1);
            if (matchingItem != null) {
                results.add(toView(work, CoverageStatus.INCLUDED, matchingItem.lineItemId(), false));
            } else if (findCatalogCandidate(work, safeLineItems) != null) {
                results.add(toView(work, CoverageStatus.NEEDS_REVIEW, null, false));
            } else if (work.suggestedCatalogItemId() != null) {
                results.add(toView(work, CoverageStatus.MISSING, null, false));
            } else {
                results.add(toView(work, CoverageStatus.NEEDS_REVIEW, null, false));
            }
        }
        return List.copyOf(results);
    }

    public CoverageResponse response(
            List<RequestedWork> requestedWorks,
            List<CoverageDecision> decisions,
            List<DraftLineItem> lineItems,
            List<String> warnings,
            List<String> acknowledgedWarningIds,
            boolean analysisFresh) {
        return response(requestedWorks, decisions, lineItems, warnings, acknowledgedWarningIds, analysisFresh, null);
    }

    public CoverageResponse response(
            List<RequestedWork> requestedWorks,
            List<CoverageDecision> decisions,
            List<DraftLineItem> lineItems,
            List<String> warnings,
            List<String> acknowledgedWarningIds,
            boolean analysisFresh,
            String quoteFingerprint) {
        List<CoverageWorkItem> workItems = evaluate(requestedWorks, decisions, lineItems);
        Set<String> acknowledged = new HashSet<>(acknowledgedWarningIds);
        List<CoverageWarning> warningItems = warningItems(warnings, acknowledged);
        List<String> unresolved = new ArrayList<>();
        for (CoverageWorkItem work : workItems) {
            if (work.status() == CoverageStatus.MISSING) {
                unresolved.add(work.name() + " – hiányzik");
            } else if (work.status() == CoverageStatus.NEEDS_REVIEW) {
                unresolved.add(work.name() + " – ellenőrizendő");
            }
        }
        for (CoverageWarning warning : warningItems) {
            if (!warning.acknowledged()) {
                unresolved.add(warning.text());
            }
        }
        if (!analysisFresh) {
            unresolved.add("A kiegészítő információk az utolsó AI-elemzés óta megváltoztak; futtass új elemzést.");
        }
        return new CoverageResponse(workItems, warningItems, analysisFresh, List.copyOf(unresolved), quoteFingerprint);
    }

    public List<CoverageWarning> warningItems(List<String> warnings, Set<String> acknowledgedIds) {
        if (warnings == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<CoverageWarning> results = new ArrayList<>();
        for (String warning : warnings) {
            if (warning == null || warning.isBlank()) {
                continue;
            }
            String id = warningId(warning);
            if (seen.add(id)) {
                results.add(new CoverageWarning(id, warning, acknowledgedIds.contains(id)));
            }
        }
        return List.copyOf(results);
    }

    public String warningId(String warning) {
        return "warning-" + sha256(normalize(warning));
    }

    public String lineItemFingerprint(DraftLineItem item) {
        return sha256(normalize(item.name()) + "|" + normalize(item.unit()) + "|"
                + normalize(item.quantity() == null ? "" : item.quantity().stripTrailingZeros().toPlainString())
                + "|" + Objects.toString(item.catalogItemId(), ""));
    }

    public String analysisFingerprint(String emailBody, String additionalNotes) {
        return sha256(Objects.toString(emailBody, "") + "\n--MIRA-ADDITIONAL-NOTES--\n"
                + Objects.toString(additionalNotes, "").trim());
    }

    public String quoteFingerprint(EmailDraft draft) {
        String lineItems = draft.getLineItems().stream()
                .map(item -> String.join("|", Objects.toString(item.lineItemId(), ""),
                        Objects.toString(item.name(), ""), Objects.toString(item.unit(), ""),
                        decimal(item.quantity()), decimal(item.unitPrice()),
                        Objects.toString(item.catalogItemId(), "")))
                .collect(Collectors.joining("\n"));
        String decisions = readDecisions(draft.getCoverageDecisionsJson()).stream()
                .sorted(Comparator.comparing(CoverageDecision::issueId))
                .map(decision -> String.join("|", decision.issueId(), decision.status().name(),
                        Objects.toString(decision.lineItemId(), ""),
                        Objects.toString(decision.lineItemFingerprint(), ""),
                        Boolean.toString(decision.userConfirmed())))
                .collect(Collectors.joining("\n"));
        String acknowledged = readStringList(draft.getAcknowledgedWarningsJson()).stream()
                .sorted().collect(Collectors.joining("\n"));
        String warnings = draft.getReviewWarnings().stream().map(this::warningId)
                .sorted().collect(Collectors.joining("\n"));
        return sha256(String.join("\n--MIRA-FINGERPRINT-SECTION--\n",
                Objects.toString(draft.getClientName(), ""),
                Objects.toString(draft.getAdditionalNotes(), ""),
                Boolean.toString(draft.isIncludeAdditionalNotes()),
                Objects.toString(draft.getAnalysisFingerprint(), ""),
                Objects.toString(draft.getRequestedWorkItemsJson(), ""),
                lineItems, decisions, acknowledged, warnings));
    }

    public List<RequestedWork> readRequestedWorks(String json) {
        return readList(json, RequestedWork.class);
    }

    public List<RequestedWork> legacyRequestedWorks(List<String> unmappedRequests, Long draftId) {
        if (unmappedRequests == null) {
            return List.of();
        }
        List<RequestedWork> works = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : unmappedRequests) {
            if (name == null || name.isBlank() || !seen.add(normalize(name))) {
                continue;
            }
            String id = "legacy-" + sha256(draftId + "|" + normalize(name));
            works.add(new RequestedWork(id, name.trim(),
                    "A korábbi elemzés nem talált egyértelmű árjegyzéki megfeleltetést.",
                    CoverageSource.UNKNOWN, null, null, null, null, null));
        }
        return List.copyOf(works);
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
            throw new IllegalStateException("Could not serialize quote coverage data", exception);
        }
    }

    public List<CoverageDecision> setDecision(
            List<CoverageDecision> existing, String issueId, CoverageStatus status,
            String lineItemId, List<DraftLineItem> lineItems) {
        List<CoverageDecision> decisions = new ArrayList<>(existing);
        decisions.removeIf(decision -> decision.issueId().equals(issueId));
        String fingerprint = null;
        if (status == CoverageStatus.INCLUDED) {
            DraftLineItem lineItem = findLineItem(lineItems, lineItemId);
            if (lineItem == null) {
                throw new IllegalArgumentException("A kiválasztott ajánlati sor már nem létezik.");
            }
            fingerprint = lineItemFingerprint(lineItem);
        } else if (status != CoverageStatus.EXCLUDED && status != CoverageStatus.NEEDS_REVIEW
                && status != CoverageStatus.MISSING) {
            throw new IllegalArgumentException("Érvénytelen ellenőrzési állapot.");
        }
        decisions.add(new CoverageDecision(issueId, status, lineItemId, fingerprint, true));
        return List.copyOf(decisions);
    }

    public List<CoverageDecision> captureInitialMatches(
            List<RequestedWork> requestedWorks, List<DraftLineItem> lineItems) {
        return evaluate(requestedWorks, List.of(), lineItems).stream()
                .filter(item -> item.status() == CoverageStatus.INCLUDED && item.linkedLineItemId() != null)
                .map(item -> {
                    DraftLineItem line = findLineItem(lineItems, item.linkedLineItemId());
                    return new CoverageDecision(item.workId(), CoverageStatus.INCLUDED, line.lineItemId(),
                            lineItemFingerprint(line), false);
                })
                .toList();
    }

    private <T> List<T> readList(String json, Class<T> itemType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, itemType));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored quote coverage data is invalid", exception);
        }
    }

    private void addWork(
            List<RequestedWork> works, Set<String> seen, String name, String reason,
            CoverageSource source, BigDecimal quantity, String unit, Long catalogId, String catalogName,
            String calculationStrategy) {
        String key = normalize(name) + "|"
                + (quantity == null ? "" : quantity.stripTrailingZeros().toPlainString());
        if (!seen.add(key)) {
            return;
        }
        String explanation = reason == null || reason.isBlank()
                ? "A rendszer ezt a munkát kérésként ismerte fel; ellenőrizd, melyik ajánlati sor fedi le."
                : reason.trim();
        BigDecimal reliableQuantity = quantity != null && quantity.signum() > 0 ? quantity : null;
        works.add(new RequestedWork(UUID.randomUUID().toString(), name.trim(), explanation, source,
                reliableQuantity, unit, catalogId, catalogName, calculationStrategy));
    }

    private CoverageSource parseSource(String source) {
        if (source == null) {
            return CoverageSource.UNKNOWN;
        }
        return switch (source.trim().toUpperCase(Locale.ROOT)) {
            case "EMAIL" -> CoverageSource.EMAIL;
            case "ADDITIONAL_INFO", "ADDITIONAL_INFORMATION" -> CoverageSource.ADDITIONAL_INFO;
            default -> CoverageSource.UNKNOWN;
        };
    }

    private DraftLineItem findAutomaticMatch(
            RequestedWork work, List<DraftLineItem> items, boolean multipleRequestsForCatalog) {
        if (work.suggestedCatalogItemId() != null
                && work.suggestedCatalogItemName() != null
                && !isGenericCatalogName(work.suggestedCatalogItemName())) {
            Optional<DraftLineItem> catalogMatch = items.stream()
                    .filter(item -> Objects.equals(item.catalogItemId(), work.suggestedCatalogItemId()))
                    .filter(item -> quantityCompatible(work, item, multipleRequestsForCatalog))
                    .findFirst();
            if (catalogMatch.isPresent()) {
                return catalogMatch.get();
            }
        }
        if (isGenericCatalogName(work.name())) {
            return null;
        }
        return items.stream()
                .filter(item -> !normalize(item.name()).isBlank())
                .filter(item -> normalize(item.name()).equals(normalize(work.name())))
                .filter(item -> quantityCompatible(work, item, false))
                .findFirst()
                .orElse(null);
    }

    private boolean isGenericCatalogName(String catalogName) {
        String normalizedName = normalize(catalogName);
        Set<String> genericWords = Set.of(
                "egyeb", "altalanos", "kivitelezesi", "munka", "munkak", "munkadij",
                "tetel", "szolgaltatas", "dij", "egyedi");
        return normalizedName.isBlank()
                || Arrays.stream(normalizedName.split(" ")).allMatch(genericWords::contains);
    }

    private DraftLineItem findCatalogCandidate(RequestedWork work, List<DraftLineItem> items) {
        if (work.suggestedCatalogItemId() == null) {
            return null;
        }
        return items.stream()
                .filter(item -> Objects.equals(item.catalogItemId(), work.suggestedCatalogItemId()))
                .findFirst()
                .orElse(null);
    }

    private boolean quantityCompatible(
            RequestedWork work, DraftLineItem item, boolean multipleRequestsForCatalog) {
        if (work.quantity() == null) {
            return !multipleRequestsForCatalog || normalize(work.name()).equals(normalize(item.name()));
        }
        if (item.quantity() == null) {
            return false;
        }
        BigDecimal expected = work.quantity();
        if (work.suggestedCalculationStrategy() != null) {
            try {
                expected = CalculationStrategy.valueOf(work.suggestedCalculationStrategy())
                        .adjustQuantity(expected, work.unit());
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return expected.compareTo(item.quantity()) == 0;
    }

    private DraftLineItem findLineItem(List<DraftLineItem> items, String lineItemId) {
        if (lineItemId == null) {
            return null;
        }
        return items.stream().filter(item -> lineItemId.equals(item.lineItemId())).findFirst().orElse(null);
    }

    private CoverageWorkItem toView(
            RequestedWork work, CoverageStatus status, String linkedLineItemId, boolean userConfirmed) {
        return new CoverageWorkItem(work.workId(), work.name(), work.explanation(), work.source(),
                work.quantity(), work.unit(), status, linkedLineItemId, userConfirmed);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
