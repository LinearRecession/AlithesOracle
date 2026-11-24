package io.alithes.oracle.service;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.GdeltQuerySanitizer;
import io.alithes.oracle.client.jev.ChoiceAnswer;
import io.alithes.oracle.client.jev.ChoiceQuestion;
import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.QueryProperties;
import io.alithes.oracle.util.QuestionTextAnalyzer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Compiles natural-language questions into sanitized GDELT DOC 2.0 search queries
 * using deterministic extraction via {@link QuestionTextAnalyzer} and Jev Choice selection.
 */
@Service
public class QueryCompiler {

    private final DecisionEngine decisionEngine;
    private final QueryProperties queryProps;

    @Autowired
    public QueryCompiler(DecisionEngine decisionEngine, OracleProperties props) {
        this(decisionEngine, Objects.requireNonNull(props, "props must not be null").query());
    }

    public QueryCompiler(DecisionEngine decisionEngine) {
        this(decisionEngine, QueryProperties.defaults());
    }

    public QueryCompiler(DecisionEngine decisionEngine, QueryProperties queryProps) {
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
        this.queryProps = Objects.requireNonNull(queryProps, "queryProps must not be null");
    }

    /**
     * Produces compiled GDELT queries (or sanitized override queries when supplied).
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

        Map<String, QuestionSpec> questions = Map.of(
                "best_query",
                new ChoiceQuestion(
                        "Which GDELT search query is most likely to find news articles that directly "
                                + "answer the question without excessive unrelated noise?",
                        candidates
                )
        );

        JevResponse response = decisionEngine.evaluate(state, questions);
        ChoiceAnswer bestQuery = response.choiceAnswer("best_query");
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
            if (sanitized.size() >= queryProps.maxOverrideQueries()) {
                break;
            }
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("All provided searchQueries failed GDELT sanitization");
        }
        return List.copyOf(sanitized);
    }

    LinkedHashMap<String, String> buildCandidates(String questionText) {
        List<String> entities = QuestionTextAnalyzer.extractEntities(questionText);
        Set<String> entityWords = QuestionTextAnalyzer.collectEntityWords(entities);
        List<String> keywords = QuestionTextAnalyzer.extractKeywords(questionText, entityWords);
        List<String> fallbackAllKeywords = QuestionTextAnalyzer.extractKeywords(questionText, Set.of());

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

    private List<String> selectTopQueries(LinkedHashMap<String, String> candidates, ChoiceAnswer bestQuery) {
        String topKey = bestQuery.choice();
        if (topKey == null || !candidates.containsKey(topKey)) {
            topKey = findHighestProbKey(candidates.keySet(), bestQuery, null);
        }
        if (topKey == null) {
            topKey = candidates.keySet().iterator().next();
        }

        int maxCompiled = queryProps.maxCompiledQueries();
        List<String> selected = new ArrayList<>(maxCompiled);
        selected.add(candidates.get(topKey));

        if (maxCompiled > 1) {
            String runnerUpKey = findHighestProbKey(candidates.keySet(), bestQuery, topKey);
            if (runnerUpKey != null && bestQuery.prob(runnerUpKey) >= queryProps.runnerUpMinProbability()) {
                String runnerUpQuery = candidates.get(runnerUpKey);
                if (!selected.contains(runnerUpQuery)) {
                    selected.add(runnerUpQuery);
                }
            }
        }
        return List.copyOf(selected);
    }

    private static String findHighestProbKey(
            Set<String> validKeys,
            ChoiceAnswer answer,
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
        return QuestionTextAnalyzer.extractEntities(text);
    }

    public static List<String> extractDistinctiveTerms(String text) {
        return QuestionTextAnalyzer.extractDistinctiveTerms(text);
    }

    private static String quotePhrase(String phrase) {
        return "\"" + phrase.replace("\"", "").trim() + "\"";
    }
}
