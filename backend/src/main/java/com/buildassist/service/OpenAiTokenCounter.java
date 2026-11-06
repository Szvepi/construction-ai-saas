package com.buildassist.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

final class OpenAiTokenCounter {

    private static final Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    private OpenAiTokenCounter() {
    }

    static int count(String text) {
        return ENCODING.encodeOrdinary(text == null ? "" : text).size();
    }

    static String truncatePrefix(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        if (count(text) <= maxTokens) {
            return text;
        }

        int codePointCount = text.codePointCount(0, text.length());
        int low = 0;
        int high = codePointCount;
        int bestOffset = 0;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int offset = text.offsetByCodePoints(0, middle);
            if (count(text.substring(0, offset)) <= maxTokens) {
                bestOffset = offset;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, bestOffset);
    }

    static String truncateSuffix(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        if (count(text) <= maxTokens) {
            return text;
        }

        int codePointCount = text.codePointCount(0, text.length());
        int low = 0;
        int high = codePointCount;
        int bestOffset = text.length();
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int offset = text.offsetByCodePoints(0, middle);
            if (count(text.substring(offset)) <= maxTokens) {
                bestOffset = offset;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return text.substring(bestOffset);
    }
}
