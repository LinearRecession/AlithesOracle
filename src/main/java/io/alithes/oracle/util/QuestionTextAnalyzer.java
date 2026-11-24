package io.alithes.oracle.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic extraction of named entities and distinctive keywords from natural-language questions.
 */
public final class QuestionTextAnalyzer {

    private static final Pattern QUOTED_PHRASE = Pattern.compile("\"([^\"]{2,})\"");
    private static final Pattern CAPITALIZED_PHRASE =
            Pattern.compile("\\b([A-Z][A-Za-z0-9'-]*(?:\\s+[A-Z][A-Za-z0-9'-]*)*)\\b");
    private static final Pattern WORD_TOKEN = Pattern.compile("[A-Za-z0-9]+");

    private static final Set<String> SENTENCE_START_STOPWORDS = Set.of(
            "has", "did", "will", "was", "have", "the", "is", "are", "were",
            "does", "do", "can", "could", "would", "should", "what", "when", "where", "who"
    );

    private static final Set<String> STOPWORDS = Set.of(
            "about", "above", "after", "again", "against", "almost", "already", "also", "although",
            "always", "among", "another", "around", "because", "been", "before", "being", "below",
            "between", "both", "could", "does", "doing", "down", "during", "each", "either", "enough",
            "even", "ever", "every", "from", "further", "have", "having", "here", "herself", "himself",
            "into", "itself", "just", "least", "less", "made", "make", "many", "more", "most", "much",
            "must", "myself", "neither", "never", "next", "none", "off", "officially", "often", "once",
            "only", "other", "ought", "ours", "ourselves", "over", "own", "perhaps", "quite", "rather",
            "really", "same", "several", "shall", "should", "since", "some", "someone", "something",
            "still", "such", "than", "that", "their", "theirs", "them", "themselves", "then", "there",
            "these", "they", "this", "those", "though", "through", "under", "until", "upon", "very",
            "want", "were", "what", "whatever", "when", "where", "whether", "which", "while", "whom",
            "whose", "will", "with", "within", "without", "would", "your", "yours", "yourself"
    );

    private QuestionTextAnalyzer() {
    }

    /**
     * Extracts quoted phrases and capitalized entity sequences from {@code text}.
     */
    public static List<String> extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> entities = new LinkedHashSet<>();

        Matcher quotedMatcher = QUOTED_PHRASE.matcher(text);
        while (quotedMatcher.find()) {
            String phrase = quotedMatcher.group(1).trim();
            if (!phrase.isEmpty()) {
                entities.add(phrase);
            }
        }

        Matcher capMatcher = CAPITALIZED_PHRASE.matcher(text);
        while (capMatcher.find()) {
            String phrase = capMatcher.group(1).trim();
            int start = capMatcher.start();
            String cleaned = stripLeadingSentenceStopword(phrase, start == 0);
            if (!cleaned.isEmpty()) {
                entities.add(cleaned);
            }
        }

        return List.copyOf(entities);
    }

    /**
     * Extracts ordered distinctive terms (entity phrases followed by non-entity keywords) from {@code text}.
     */
    public static List<String> extractDistinctiveTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> entities = extractEntities(text);
        Set<String> entityWords = collectEntityWords(entities);
        List<String> keywords = extractKeywords(text, entityWords);
        LinkedHashSet<String> combined = new LinkedHashSet<>(entities);
        combined.addAll(keywords);
        return List.copyOf(combined);
    }

    /**
     * Tokenizes distinctive terms into lowercase search tokens of at least {@code minTokenLength} characters.
     */
    public static Set<String> extractSearchTokens(String text, int minTokenLength) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String term : extractDistinctiveTerms(text)) {
            for (String token : term.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.length() >= minTokenLength) {
                    tokens.add(token);
                }
            }
        }
        return Set.copyOf(tokens);
    }

    /**
     * Collects lowercase alphanumeric words appearing in {@code entities}.
     */
    public static Set<String> collectEntityWords(List<String> entities) {
        if (entities == null || entities.isEmpty()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        for (String entity : entities) {
            Matcher m = WORD_TOKEN.matcher(entity.toLowerCase(Locale.ROOT));
            while (m.find()) {
                words.add(m.group());
            }
        }
        return Set.copyOf(words);
    }

    /**
     * Extracts non-stopword alphanumeric keywords (4+ chars) from {@code text}, excluding {@code excludedWords}.
     */
    public static List<String> extractKeywords(String text, Set<String> excludedWords) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> excluded = excludedWords == null ? Set.of() : excludedWords;
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        Matcher matcher = WORD_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() < 4) {
                continue;
            }
            if (STOPWORDS.contains(token) || excluded.contains(token)) {
                continue;
            }
            keywords.add(token);
        }
        return List.copyOf(keywords);
    }

    private static String stripLeadingSentenceStopword(String phrase, boolean atSentenceStart) {
        if (!atSentenceStart) {
            return phrase;
        }
        String[] parts = phrase.split("\\s+");
        if (parts.length == 0) {
            return "";
        }
        if (SENTENCE_START_STOPWORDS.contains(parts[0].toLowerCase(Locale.ROOT))) {
            if (parts.length == 1) {
                return "";
            }
            return String.join(" ", List.of(parts).subList(1, parts.length));
        }
        return phrase;
    }
}
