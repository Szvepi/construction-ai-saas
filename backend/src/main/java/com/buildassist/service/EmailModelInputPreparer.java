package com.buildassist.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

final class EmailModelInputPreparer {

    private static final Pattern HTML_MARKUP = Pattern.compile(
            "(?is)<\\s*(?:html|body|div|p|br|table|tr|td|span|ul|ol|li|blockquote|script|style)\\b");
    private static final Pattern QUOTED_MESSAGE = Pattern.compile(
            "(?im)^\\s*(?:On .{3,240} wrote:|[-_ ]{2,}Original Message[-_ ]{2,}|From:\\s*\\S.*|>)");
    private static final Pattern FORWARDED_MESSAGE = Pattern.compile(
            "(?im)^\\s*(?:Begin forwarded message:|[-_ ]{2,}Forwarded message[-_ ]{2,})\\s*$");
    private static final Pattern SIGNATURE = Pattern.compile("(?im)^\\s*--\\s*$");

    private EmailModelInputPreparer() {
    }

    static String prepare(String originalBody, int maxTokens) {
        String cleaned = clean(originalBody);
        if (cleaned.isBlank() || maxTokens <= 0) {
            return "";
        }

        if (FORWARDED_MESSAGE.matcher(cleaned).find()) {
            return truncateKeepingEnds(cleaned, maxTokens);
        }

        Matcher quoted = QUOTED_MESSAGE.matcher(cleaned);
        if (!quoted.find()) {
            return OpenAiTokenCounter.truncatePrefix(stripSignature(cleaned), maxTokens);
        }

        String current = stripSignature(cleaned.substring(0, quoted.start())).stripTrailing();
        String history = cleaned.substring(quoted.start()).strip();
        if (current.isBlank()) {
            return OpenAiTokenCounter.truncatePrefix(history, maxTokens);
        }

        int currentBudget = Math.max(1, (int) (maxTokens * 0.6));
        String currentPart = OpenAiTokenCounter.truncatePrefix(current, currentBudget);
        int remaining = maxTokens - OpenAiTokenCounter.count(currentPart)
                - OpenAiTokenCounter.count("\n[Quoted previous message]\n");
        String historyPart = OpenAiTokenCounter.truncatePrefix(history, Math.max(0, remaining));
        String result = currentPart + "\n[Quoted previous message]\n" + historyPart;
        if (OpenAiTokenCounter.count(result) > maxTokens) {
            return OpenAiTokenCounter.truncatePrefix(result, maxTokens);
        }
        return result.strip();
    }

    private static String stripSignature(String text) {
        Matcher signature = SIGNATURE.matcher(text);
        return signature.find() ? text.substring(0, signature.start()).stripTrailing() : text;
    }

    private static String clean(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }

        String text = body;
        if (HTML_MARKUP.matcher(body).find()) {
            Document document = Jsoup.parseBodyFragment(body);
            document.select("script, style, head, title").remove();
            for (Element element : document.select("br, hr")) {
                element.after(new TextNode("\n"));
            }
            for (Element element : document.select("p, div, li, blockquote, tr, h1, h2, h3")) {
                element.before(new TextNode("\n"));
                element.after(new TextNode("\n"));
            }
            text = document.body().wholeText();
        }

        return text.replace('\u00a0', ' ')
                .replaceAll("(?m)[\\t\\x0B\\f\\r ]+$", "")
                .replaceAll("(?m)^\\s+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String truncateKeepingEnds(String text, int maxTokens) {
        if (OpenAiTokenCounter.count(text) <= maxTokens) {
            return text;
        }
        String marker = "\n[Middle of forwarded message omitted]\n";
        int contentBudget = Math.max(0, maxTokens - OpenAiTokenCounter.count(marker));
        int prefixBudget = Math.max(1, (int) (contentBudget * 0.6));
        int suffixBudget = Math.max(0, contentBudget - prefixBudget);
        String prefix = OpenAiTokenCounter.truncatePrefix(text, prefixBudget);
        String suffix = OpenAiTokenCounter.truncateSuffix(text, suffixBudget);
        return (prefix + marker + suffix).strip();
    }
}
