package com.buildassist.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EmailClassificationPrefilter {

    private static final Logger log = LoggerFactory.getLogger(EmailClassificationPrefilter.class);

    private EmailClassificationPrefilter() {
    }

    static Result evaluate(String metadata, ObjectMapper objectMapper) {
        Map<String, String> headers;
        try {
            JsonNode parsed = objectMapper.readTree(metadata == null ? "{}" : metadata);
            if (parsed == null || !parsed.isObject()) {
                return Result.undecided();
            }
            java.util.LinkedHashMap<String, String> extracted = new java.util.LinkedHashMap<>();
            parsed.fields().forEachRemaining(entry ->
                    extracted.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().asText("")));
            headers = extracted;
        } catch (JsonProcessingException ex) {
            log.warn("CLASSIFICATION_PREFILTER_METADATA_INVALID");
            return Result.undecided();
        }

        if (has(headers, "content-type", "report-type=delivery-status")
                || (has(headers, "x-failed-recipients")
                    && hasFirstValue(headers, "auto-submitted", "auto-generated"))) {
            return Result.excluded("AUTO_BOUNCE_DSN_V1", "Egyértelmű automatikus kézbesítési hiba.");
        }

        if (hasFirstValue(headers, "auto-submitted", "auto-replied")
                || hasExactValue(headers, "x-autoreply", "yes")
                || hasExactValue(headers, "x-autorespond", "yes")) {
            return Result.excluded("AUTO_REPLY_V1", "Automatikus válasz fejléc alapján.");
        }

        boolean mailingListIdentity = has(headers, "list-id");
        boolean unsubscribe = has(headers, "list-unsubscribe");
        boolean bulkPrecedence = hasExactValue(headers, "precedence", "bulk")
                || hasExactValue(headers, "precedence", "junk");
        boolean generatedListMessage = hasFirstValue(headers, "auto-submitted", "auto-generated");
        if (mailingListIdentity && unsubscribe && (bulkPrecedence || generatedListMessage)) {
            return Result.excluded("NEWSLETTER_HEADERS_V1", "Több, egymást megerősítő levelezőlista-fejléc.");
        }

        return Result.undecided();
    }

    private static boolean has(Map<String, String> headers, String name) {
        return has(headers, name, null);
    }

    private static boolean has(Map<String, String> headers, String name, String expectedValue) {
        String value = headers.get(name);
        if (value == null || value.isBlank()) {
            return false;
        }
        return expectedValue == null || value.toLowerCase(Locale.ROOT).contains(expectedValue);
    }

    private static boolean hasExactValue(Map<String, String> headers, String name, String expectedValue) {
        String value = headers.get(name);
        return value != null && value.trim().equalsIgnoreCase(expectedValue);
    }

    private static boolean hasFirstValue(Map<String, String> headers, String name, String expectedValue) {
        String value = headers.get(name);
        if (value == null) {
            return false;
        }
        int parameterStart = value.indexOf(';');
        String firstValue = parameterStart < 0 ? value : value.substring(0, parameterStart);
        return firstValue.trim().equalsIgnoreCase(expectedValue);
    }

    record Result(boolean excluded, String ruleId, String reason) {
        static Result undecided() {
            return new Result(false, null, null);
        }

        static Result excluded(String ruleId, String reason) {
            return new Result(true, ruleId, reason);
        }
    }
}
