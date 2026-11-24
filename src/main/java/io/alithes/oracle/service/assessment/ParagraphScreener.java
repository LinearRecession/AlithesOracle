package io.alithes.oracle.service.assessment;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.FetchedArticle;
import io.alithes.oracle.client.JevContextTooLargeException;
import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.NoulQuestion;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.config.AssessorProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.util.QuestionTextAnalyzer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Selects the most relevant article paragraphs for Jev stance assessment using keyword pre-ranking
 * and batched Jev Noul screening on long articles.
 */
@Component
public class ParagraphScreener {

    private static final Logger log = LoggerFactory.getLogger(ParagraphScreener.class);

    private final DecisionEngine decisionEngine;
    private final AssessorProperties assessorProps;

    @Autowired
    public ParagraphScreener(DecisionEngine decisionEngine, OracleProperties props) {
        this(decisionEngine, Objects.requireNonNull(props, "props must not be null").assessor());
    }

    public ParagraphScreener(DecisionEngine decisionEngine, AssessorProperties assessorProps) {
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
        this.assessorProps = Objects.requireNonNull(assessorProps, "assessorProps must not be null");
    }

    public List<String> selectParagraphs(Question q, String title, FetchedArticle fetched) {
        if (fetched.status() == FetchStatus.TITLE_ONLY || fetched.paragraphs().isEmpty()) {
            return List.of(title);
        }

        List<String> paragraphs = fetched.paragraphs();
        if (paragraphs.size() <= assessorProps.shortArticleMaxParagraphs()
                && fetched.joinedText().length() <= assessorProps.shortArticleMaxChars()) {
            return paragraphs;
        }

        List<String> candidates = selectCandidates(
                q.text(), paragraphs, assessorProps.screeningMaxInputParagraphs());

        Map<String, String> paragraphMap = new LinkedHashMap<>();
        Map<String, QuestionSpec> screenQuestions = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            String key = "p" + i;
            paragraphMap.put(key, candidates.get(i));
            screenQuestions.put(key, new NoulQuestion(
                    "Does paragraphs." + key + " discuss the event in question?"
            ));
        }

        Map<String, Object> screenState = new LinkedHashMap<>();
        screenState.put("question", q.text());
        screenState.put("paragraphs", paragraphMap);

        try {
            JevResponse screenResponse = decisionEngine.evaluate(screenState, screenQuestions);
            return pickScreenedParagraphs(candidates, screenResponse);
        } catch (JevContextTooLargeException e) {
            log.warn("Paragraph screening exceeded Jev context limit for question {}; truncating to lead paragraphs",
                    q.id());
            return truncateParagraphs(
                    candidates, assessorProps.retryMaxParagraphs(), assessorProps.retryMaxChars());
        }
    }

    /**
     * Keeps the lead paragraph plus the highest keyword-matching subsequent paragraphs up to
     * {@code maxInputParagraphs} so long encyclopedia/news pages preserve deep relevant sections.
     */
    public static List<String> selectCandidates(
            String questionText,
            List<String> paragraphs,
            int maxInputParagraphs
    ) {
        if (paragraphs.size() <= maxInputParagraphs) {
            return paragraphs;
        }
        Set<String> searchTerms = QuestionTextAnalyzer.extractSearchTokens(questionText, 4);

        record IndexedScore(int index, int hits) {
        }
        List<IndexedScore> ranked = new ArrayList<>(paragraphs.size() - 1);
        for (int i = 1; i < paragraphs.size(); i++) {
            String lower = paragraphs.get(i).toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String term : searchTerms) {
                if (lower.contains(term)) {
                    hits++;
                }
            }
            ranked.add(new IndexedScore(i, hits));
        }

        ranked.sort(Comparator.comparingInt(IndexedScore::hits).reversed()
                .thenComparingInt(IndexedScore::index));

        int remainingSlots = Math.max(0, maxInputParagraphs - 1);
        List<Integer> pickedIndices = new ArrayList<>(maxInputParagraphs);
        pickedIndices.add(0);
        ranked.stream()
                .limit(remainingSlots)
                .map(IndexedScore::index)
                .sorted()
                .forEach(pickedIndices::add);

        List<String> selected = new ArrayList<>(pickedIndices.size());
        for (int idx : pickedIndices) {
            selected.add(paragraphs.get(idx));
        }
        return List.copyOf(selected);
    }

    public static List<String> truncateParagraphs(List<String> paragraphs, int maxParagraphs, int maxChars) {
        if (paragraphs.isEmpty()) {
            return List.of("");
        }
        List<String> result = new ArrayList<>(Math.min(maxParagraphs, paragraphs.size()));
        int remaining = maxChars;
        for (int i = 0; i < paragraphs.size() && i < maxParagraphs && remaining > 0; i++) {
            String p = paragraphs.get(i);
            if (p.length() <= remaining) {
                result.add(p);
                remaining -= p.length();
            } else {
                result.add(p.substring(0, remaining));
                remaining = 0;
            }
        }
        return result.isEmpty() ? List.of("") : List.copyOf(result);
    }

    private List<String> pickScreenedParagraphs(List<String> candidates, JevResponse screenResponse) {
        record ScoredParagraph(int index, String text, double score) {
        }

        List<ScoredParagraph> passingNonLead = new ArrayList<>();
        for (int i = 1; i < candidates.size(); i++) {
            double score = screenResponse.noulProbability("p" + i);
            if (score >= assessorProps.screeningMinProbability()) {
                passingNonLead.add(new ScoredParagraph(i, candidates.get(i), score));
            }
        }
        passingNonLead.sort(Comparator.comparingDouble(ScoredParagraph::score).reversed()
                .thenComparingInt(ScoredParagraph::index));

        List<String> selected = new ArrayList<>();
        int totalChars = 0;

        String lead = candidates.get(0);
        selected.add(lead);
        totalChars += lead.length();

        int maxSelected = assessorProps.screeningMaxSelectedParagraphs();
        int maxSelectedChars = assessorProps.screeningMaxSelectedChars();
        for (ScoredParagraph sp : passingNonLead) {
            if (selected.size() >= maxSelected) {
                break;
            }
            if (totalChars + sp.text().length() > maxSelectedChars) {
                continue;
            }
            selected.add(sp.text());
            totalChars += sp.text().length();
        }
        return List.copyOf(selected);
    }
}
