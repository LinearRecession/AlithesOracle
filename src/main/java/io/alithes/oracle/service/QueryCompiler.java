package io.alithes.oracle.service;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.GdeltQuerySanitizer;
import io.alithes.oracle.client.JevModels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Compiles natural-language questions into 1-2 sanitized GDELT DOC 2.0 search queries
 * using deterministic extraction in Java and Jev Choice selection when multiple candidates exist.
 */
@Service
public class QueryCompiler {

    private static final int MAX_OVERRIDE_QUERIES = 3;
    private static final int MAX_COMPILED_QUERIES = 2;
    private static final double RUNNER_UP_MIN_PROBABILITY = 0.25;

    private static final Pattern QUOTED_PHRASE = Pattern.compile("\"([^\"]{2,})\"");
    private static final Pattern CAPITALIZED_PHRASE = Pattern.compile("\\b([A-Z][A-Za-z0-9'-]*(?:\\s+[A-Z][A-Za-z0-9'-]*)*)\\b");
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

    private final DecisionEngine decisionEngine;

    public QueryCompiler(DecisionEngine decisionEngine) {
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
    }

    /**
     * Produces 1-2 compiled GDELT queries (or up to 3 sanitized override queries when supplied).
     */
    public List<String> compile(String questionText, String resolutionCriteria, List<String> searchQueriesOverride) {
        if (questionText == null || questionText.isBlank()) {
            throw new IllegalArgumentException("questionText must not be blank");
        }

        if (searchQueriesOverride != null && !searchQueriesOverride.isEmpty()) {
            return sanitizeOverrides(searchQueriesOverride);
        }

        LinkedHashMap<String, String> candidates = buildCandidates(questionText);
        if (candidates.isEmpty()) {
            return List.of(GdeltQuerySanitizer.sanitize(questionText));
        }
        if (candidates.size() == 1) {
            return List.of(candidates.values().iterator().next());
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", questionText.trim());
        state.put("resolution_criteria", resolutionCriteria == null ? "" : resolutionCriteria.trim());

        Map<String, JevModels.QuestionSpec> questions = Map.of(
                "best_query",
                new JevModels.ChoiceQuestion(
                        "Which GDELT search query is most likely to find news articles that directly "
                                + "answer the question without excessive unrelated noise?",
                        candidates
                )
        );

        JevModels.JevResponse response = decisionEngine.evaluate(state, questions);
        JevModels.ChoiceAnswer bestQuery = response.choiceAnswer("best_query");
        return selectTopQueries(candidates, bestQuery);
    }

    private List<String> sanitizeOverrides(List<String> overrides) {
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String raw : overrides) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                sanitized.add(GdeltQuerySanitizer.sanitize(raw));
            } catch (IllegalArgumentException ignored) {
                // Skip invalid individual overrides; reject below if none survive.
            }
            if (sanitized.size() >= MAX_OVERRIDE_QUERIES) {
                break;
            }
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("All provided searchQueries failed GDELT sanitization");
        }
        return List.copyOf(sanitized);
    }

    LinkedHashMap<String, String> buildCandidates(String questionText) {
        List<String> entities = extractEntities(questionText);
        Set<String> entityWords = collectEntityWords(entities);
        List<String> keywords = extractKeywords(questionText, entityWords);
        List<String> fallbackAllKeywords = extractKeywords(questionText, Set.of());

        LinkedHashMap<String, String> candidates = new LinkedHashMap<>();
        Set<String> seenQueries = new LinkedHashSet<>();
        int index = 1;

        String topEntity = entities.isEmpty() ? null : entities.get(0);
        String secondEntity = entities.size() > 1 ? entities.get(1) : null;

        // q1: Top entity phrase (quoted) + top 2 non-entity keywords
        if (topEntity != null && !keywords.isEmpty()) {
            StringBuilder q1 = new StringBuilder(quotePhrase(topEntity));
            q1.append(' ').append(keywords.get(0));
            if (keywords.size() >= 2) {
                q1.append(' ').append(keywords.get(1));
            } else if (secondEntity != null) {
                q1.append(' ').append(quotePhrase(secondEntity));
            }
            index = addCandidate(candidates, seenQueries, index, q1.toString());
        } else if (topEntity != null && secondEntity != null) {
            index = addCandidate(candidates, seenQueries, index,
                    quotePhrase(topEntity) + " " + quotePhrase(secondEntity));
        }

        // q2: Top entity phrase (quoted) + (keyword1 OR keyword2)
        if (topEntity != null && keywords.size() >= 2) {
            String q2 = quotePhrase(topEntity) + " (" + keywords.get(0) + " OR " + keywords.get(1) + ")";
            index = addCandidate(candidates, seenQueries, index, q2);
        }

        // q3: Top 3-4 distinctive keywords joined with spaces
        List<String> q3Terms = keywords.size() >= 3 ? keywords : fallbackAllKeywords;
        if (!q3Terms.isEmpty()) {
            int limit = Math.min(4, q3Terms.size());
            String q3 = String.join(" ", q3Terms.subList(0, limit));
            index = addCandidate(candidates, seenQueries, index, q3);
        }

        // q4: Top 2 distinctive keywords joined with spaces (broadest fallback)
        List<String> q4Terms = keywords.size() >= 2 ? keywords : fallbackAllKeywords;
        if (q4Terms.size() >= 2) {
            String q4 = q4Terms.get(0) + " " + q4Terms.get(1);
            addCandidate(candidates, seenQueries, index, q4);
        } else if (q4Terms.size() == 1) {
            addCandidate(candidates, seenQueries, index, q4Terms.get(0));
        }

        return candidates;
    }

    private List<String> selectTopQueries(LinkedHashMap<String, String> candidates, JevModels.ChoiceAnswer bestQuery) {
        String topKey = bestQuery.choice();
        if (topKey == null || !candidates.containsKey(topKey)) {
            topKey = findHighestProbKey(candidates.keySet(), bestQuery, null);
        }
        if (topKey == null) {
            topKey = candidates.keySet().iterator().next();
        }

        List<String> selected = new ArrayList<>(MAX_COMPILED_QUERIES);
        selected.add(candidates.get(topKey));

        String runnerUpKey = findHighestProbKey(candidates.keySet(), bestQuery, topKey);
        if (runnerUpKey != null && bestQuery.prob(runnerUpKey) >= RUNNER_UP_MIN_PROBABILITY) {
            String runnerUpQuery = candidates.get(runnerUpKey);
            if (!selected.contains(runnerUpQuery)) {
                selected.add(runnerUpQuery);
            }
        }
        return List.copyOf(selected);
    }

    private static String findHighestProbKey(
            Set<String> validKeys,
            JevModels.ChoiceAnswer answer,
            String excludeKey
    ) {
        String bestKey = null;
        double bestProb = -1.0;
        for (String key : validKeys) {
            if (key.equals(excludeKey)) {
                continue;
            }
            double prob = answer.prob(key);
            if (prob > bestProb) {
                bestProb = prob;
                bestKey = key;
            }
        }
        return bestKey;
    }

    private static int addCandidate(
            LinkedHashMap<String, String> candidates,
            Set<String> seenQueries,
            int index,
            String rawQuery
    ) {
        if (candidates.size() >= 4 || rawQuery == null || rawQuery.isBlank()) {
            return index;
        }
        try {
            String sanitized = GdeltQuerySanitizer.sanitize(rawQuery);
            if (seenQueries.add(sanitized)) {
                candidates.put("q" + index, sanitized);
                return index + 1;
            }
        } catch (IllegalArgumentException ignored) {
            // Skip invalid candidate.
        }
        return index;
    }

    public static List<String> extractEntities(String text) {
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

    private static Set<String> collectEntityWords(List<String> entities) {
        Set<String> words = new LinkedHashSet<>();
        for (String entity : entities) {
            Matcher m = WORD_TOKEN.matcher(entity.toLowerCase(Locale.ROOT));
            while (m.find()) {
                words.add(m.group());
            }
        }
        return words;
    }

    private static List<String> extractKeywords(String text, Set<String> excludedWords) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        Matcher matcher = WORD_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() < 4) {
                continue;
            }
            if (STOPWORDS.contains(token) || excludedWords.contains(token)) {
                continue;
            }
            keywords.add(token);
        }
        return List.copyOf(keywords);
    }

    private static String quotePhrase(String phrase) {
        return "\"" + phrase.replace("\"", "").trim() + "\"";
    }
}
