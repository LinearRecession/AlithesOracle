package io.alithes.oracle.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heuristic detector for prompt-injection payloads embedded in scraped news text.
 */
public final class PromptInjectionDetector {

    private static final Pattern CONTROL_AND_FORMAT_CHARS = Pattern.compile("[\\p{Cf}\\p{Cc}]+");
    private static final Pattern MULTI_WHITESPACE = Pattern.compile("\\s+");

    private static final List<String> SUSPICIOUS_PHRASES = List.of(
            "ignore previous instructions",
            "ignore all previous",
            "disregard previous",
            "disregard all previous",
            "disregard the above",
            "forget your instructions",
            "override system",
            "new instructions:",
            "system prompt",
            "answer yes to all",
            "output confirms_occurred",
            "you must answer yes"
    );

    private PromptInjectionDetector() {
    }

    public static boolean isSuspicious(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String withoutInvisible = CONTROL_AND_FORMAT_CHARS.matcher(text).replaceAll(" ");
        String normalized = MULTI_WHITESPACE.matcher(withoutInvisible)
                .replaceAll(" ")
                .trim()
                .toLowerCase(Locale.ROOT);
        for (String phrase : SUSPICIOUS_PHRASES) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
