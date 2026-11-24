package io.alithes.oracle.service;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.FetchedArticle;
import io.alithes.oracle.client.JevContextTooLargeException;
import io.alithes.oracle.client.JevModels;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.util.PromptInjectionDetector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates fetched article evidence against a question using optional Jev paragraph screening
 * and dual-order stance elicitation.
 */
@Service
public class EvidenceAssessor {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAssessor.class);

    private static final int SHORT_ARTICLE_MAX_PARAGRAPHS = 8;
    private static final int SHORT_ARTICLE_MAX_CHARS = 4000;
    private static final int SCREENING_MAX_INPUT_PARAGRAPHS = 25;
    private static final double SCREENING_MIN_PROBABILITY = 0.45;
    private static final int SCREENING_MAX_SELECTED_PARAGRAPHS = 8;
    private static final int SCREENING_MAX_SELECTED_CHARS = 6000;
    private static final int RETRY_MAX_PARAGRAPHS = 3;
    private static final int RETRY_MAX_CHARS = 1500;
    private static final double STANCE_DELTA_UNSTABLE_THRESHOLD = 0.25;
    private static final int SYNTHESIS_MAX_REPORTS = 8;
    private static final int SYNTHESIS_MAX_EXCERPT_CHARS = 400;
    private static final DateTimeFormatter SYNTHESIS_DATE_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    public record ConsensusSynthesis(
            String model,
            double pConsensusYes,
            double pConsensusNo,
            double pSufficient
    ) {
    }

    private static final LinkedHashMap<String, String> RELEVANT_CRITERIA = createRelevantCriteria();
    private static final LinkedHashMap<String, String> STANCE_CRITERIA_FORWARD = createStanceCriteriaForward();
    private static final LinkedHashMap<String, String> STANCE_CRITERIA_REVERSE = createStanceCriteriaReverse();
    private static final LinkedHashMap<String, String> ATTRIBUTION_CRITERIA = createAttributionCriteria();

    private final Clock clock;
    private final DecisionEngine decisionEngine;

    public EvidenceAssessor(Clock clock, DecisionEngine decisionEngine) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
    }

    /**
     * Assesses {@code ev} and its {@code fetched} content against {@code q}.
     */
    public Assessment assess(Question q, Evidence ev, FetchedArticle fetched) {
        Objects.requireNonNull(q, "question must not be null");
        Objects.requireNonNull(ev, "evidence must not be null");
        Objects.requireNonNull(fetched, "fetched must not be null");

        String title = ev.title() == null ? "" : ev.title();
        boolean injection = PromptInjectionDetector.isSuspicious(title + " " + fetched.joinedText());

        List<String> selectedParagraphs = selectParagraphs(q, title, fetched);
        JevModels.JevResponse response = evaluateWithContextRetry(q, ev, selectedParagraphs);

        double pRelevant = response.noulProbability("relevant");
        double pStanceFits = response.noulProbability("stance_fits");
        JevModels.ChoiceAnswer stanceA = response.choiceAnswer("stance_a");
        JevModels.ChoiceAnswer stanceB = response.choiceAnswer("stance_b");
        double pFresh = response.noulProbability("is_recent");
        String attribution = response.choice("attribution");

        double pConfirms = averageProb(stanceA, stanceB, "confirms_occurred");
        double pPlanned = averageProb(stanceA, stanceB, "planned_or_future");
        double pSpeculation = averageProb(stanceA, stanceB, "speculation_or_rumor");
        double pDenies = averageProb(stanceA, stanceB, "denies_or_not_occurred");

        boolean stanceUnstable = !Objects.equals(stanceA.choice(), stanceB.choice())
                || Math.abs(stanceA.prob("confirms_occurred") - stanceB.prob("confirms_occurred"))
                        > STANCE_DELTA_UNSTABLE_THRESHOLD;
        double stanceConfidence = Math.min(stanceA.confidence(), stanceB.confidence());

        return new Assessment(
                UUID.randomUUID(),
                ev.id(),
                q.id(),
                response.model() == null ? "" : response.model(),
                pRelevant,
                pStanceFits,
                pConfirms,
                pPlanned,
                pSpeculation,
                pDenies,
                stanceUnstable,
                stanceConfidence,
                pFresh,
                attribution == null ? "" : attribution,
                injection,
                response.inputTokens(),
                response.requestSha256(),
                response.rawResponseBody(),
                clock.instant()
        );
    }

    /**
     * Synthesizes top assessed reports in a single Jev call to adjudicate partial-evidence disputes.
     */
    public ConsensusSynthesis synthesize(Question q, List<ResolutionEngine.EvidenceWithAssessment> items) {
        Objects.requireNonNull(q, "question must not be null");
        List<ResolutionEngine.EvidenceWithAssessment> safeItems = items == null ? List.of() : items;

        List<ResolutionEngine.EvidenceWithAssessment> topItems = safeItems.stream()
                .filter(i -> i != null && i.evidence() != null && i.assessment() != null && !i.assessment().injectionFlag())
                .sorted(Comparator.comparingDouble(
                        (ResolutionEngine.EvidenceWithAssessment i) -> i.assessment().pRelevant()
                                * Math.max(i.assessment().pConfirms(), i.assessment().pDenies())).reversed())
                .limit(SYNTHESIS_MAX_REPORTS)
                .sorted(Comparator.comparing(
                        (ResolutionEngine.EvidenceWithAssessment i) -> effectiveDate(i.evidence()),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Map<String, Object>> reports = new ArrayList<>(topItems.size());
        for (ResolutionEngine.EvidenceWithAssessment item : topItems) {
            Evidence ev = item.evidence();
            String excerpt = ev.excerpt() == null ? "" : ev.excerpt();
            if (excerpt.length() > SYNTHESIS_MAX_EXCERPT_CHARS) {
                excerpt = excerpt.substring(0, SYNTHESIS_MAX_EXCERPT_CHARS);
            }
            Instant date = effectiveDate(ev);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("date", date == null ? "" : SYNTHESIS_DATE_FORMAT.format(date));
            report.put("domain", ev.domain() == null ? "" : ev.domain());
            report.put("title", ev.title() == null ? "" : ev.title());
            report.put("fetchStatus", ev.fetchStatus().name());
            report.put("excerpt", excerpt);
            reports.add(report);
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", q.text());
        state.put("resolution_criteria", q.resolutionCriteria() == null ? "" : q.resolutionCriteria());
        state.put("reports", reports);

        Map<String, JevModels.QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("consensus_yes", new JevModels.NoulQuestion(
                "Based on the most recent reports in `reports`, is the answer to `question` YES?"
        ));
        questions.put("consensus_no", new JevModels.NoulQuestion(
                "Based on the most recent reports in `reports`, is the answer to `question` NO?"
        ));
        questions.put("consensus_clear", new JevModels.NoulQuestion(
                "Do the most recent reports in `reports` give a clear, consistent answer to `question` "
                        + "(as opposed to being genuinely conflicting or inconclusive)?"
        ));

        JevModels.JevResponse response = decisionEngine.evaluate(state, questions);
        return new ConsensusSynthesis(
                response.model() == null ? "" : response.model(),
                response.noulProbability("consensus_yes"),
                response.noulProbability("consensus_no"),
                response.noulProbability("consensus_clear")
        );
    }

    private static Instant effectiveDate(Evidence ev) {
        return ev.publishedAt() != null ? ev.publishedAt() : ev.seenAt();
    }

    private List<String> selectParagraphs(Question q, String title, FetchedArticle fetched) {
        if (fetched.status() == FetchStatus.TITLE_ONLY || fetched.paragraphs().isEmpty()) {
            return List.of(title);
        }

        List<String> paragraphs = fetched.paragraphs();
        if (paragraphs.size() <= SHORT_ARTICLE_MAX_PARAGRAPHS
                && fetched.joinedText().length() <= SHORT_ARTICLE_MAX_CHARS) {
            return paragraphs;
        }

        List<String> candidates = selectScreeningCandidates(q.text(), paragraphs);

        Map<String, String> paragraphMap = new LinkedHashMap<>();
        Map<String, JevModels.QuestionSpec> screenQuestions = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            String key = "p" + i;
            paragraphMap.put(key, candidates.get(i));
            screenQuestions.put(key, new JevModels.NoulQuestion(
                    "Does paragraphs." + key + " discuss the event in question?"
            ));
        }

        Map<String, Object> screenState = new LinkedHashMap<>();
        screenState.put("question", q.text());
        screenState.put("paragraphs", paragraphMap);

        try {
            JevModels.JevResponse screenResponse = decisionEngine.evaluate(screenState, screenQuestions);
            return pickScreenedParagraphs(candidates, screenResponse);
        } catch (JevContextTooLargeException e) {
            log.warn("Paragraph screening exceeded Jev context limit for question {}; truncating to lead paragraphs",
                    q.id());
            return truncateParagraphs(candidates, RETRY_MAX_PARAGRAPHS, RETRY_MAX_CHARS);
        }
    }

    static List<String> selectScreeningCandidates(String questionText, List<String> paragraphs) {
        if (paragraphs.size() <= SCREENING_MAX_INPUT_PARAGRAPHS) {
            return paragraphs;
        }
        Set<String> searchTerms = new LinkedHashSet<>();
        for (String term : QueryCompiler.extractDistinctiveTerms(questionText)) {
            for (String token : term.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.length() >= 4) {
                    searchTerms.add(token);
                }
            }
        }

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

        int remainingSlots = SCREENING_MAX_INPUT_PARAGRAPHS - 1;
        List<Integer> pickedIndices = new ArrayList<>(SCREENING_MAX_INPUT_PARAGRAPHS);
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

    private static List<String> pickScreenedParagraphs(List<String> candidates, JevModels.JevResponse screenResponse) {
        record ScoredParagraph(int index, String text, double score) {
        }

        List<ScoredParagraph> passingNonLead = new ArrayList<>();
        for (int i = 1; i < candidates.size(); i++) {
            double score = screenResponse.noulProbability("p" + i);
            if (score >= SCREENING_MIN_PROBABILITY) {
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

        for (ScoredParagraph sp : passingNonLead) {
            if (selected.size() >= SCREENING_MAX_SELECTED_PARAGRAPHS) {
                break;
            }
            if (totalChars + sp.text().length() > SCREENING_MAX_SELECTED_CHARS) {
                continue;
            }
            selected.add(sp.text());
            totalChars += sp.text().length();
        }
        return List.copyOf(selected);
    }

    private JevModels.JevResponse evaluateWithContextRetry(Question q, Evidence ev, List<String> selectedParagraphs) {
        Map<String, JevModels.QuestionSpec> questions = buildAssessmentQuestions();
        try {
            return decisionEngine.evaluate(buildAssessmentState(q, ev, selectedParagraphs), questions);
        } catch (JevContextTooLargeException tooLarge) {
            log.warn("Main assessment exceeded Jev context limit for evidence {}; retrying with truncated paragraphs",
                    ev.id());
            List<String> truncated = truncateParagraphs(selectedParagraphs, RETRY_MAX_PARAGRAPHS, RETRY_MAX_CHARS);
            return decisionEngine.evaluate(buildAssessmentState(q, ev, truncated), questions);
        }
    }

    private static Map<String, Object> buildAssessmentState(Question q, Evidence ev, List<String> paragraphs) {
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", ev.title() == null ? "" : ev.title());
        article.put("domain", ev.domain() == null ? "" : ev.domain());
        article.put("paragraphs", paragraphs);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", q.text());
        state.put("resolution_criteria", q.resolutionCriteria() == null ? "" : q.resolutionCriteria());
        state.put("article", article);
        return state;
    }

    private static Map<String, JevModels.QuestionSpec> buildAssessmentQuestions() {
        Map<String, JevModels.QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("relevant", new JevModels.NoulQuestion(
                "Is `article` about the specific event described in `question`?",
                RELEVANT_CRITERIA
        ));
        questions.put("stance_fits", new JevModels.NoulQuestion(
                "Does `article` state or imply anything about whether the event in `question` has happened or not?"
        ));
        questions.put("stance_a", new JevModels.ChoiceQuestion(
                "What does `article` report about the event in `question`?",
                STANCE_CRITERIA_FORWARD
        ));
        questions.put("stance_b", new JevModels.ChoiceQuestion(
                "What does `article` report about the event in `question`?",
                STANCE_CRITERIA_REVERSE
        ));
        questions.put("is_recent", new JevModels.NoulQuestion(
                "Does `article` report the event as newly occurring around the time the article was published, "
                        + "rather than recounting an older historical event from months or years ago?"
        ));
        questions.put("attribution", new JevModels.ChoiceQuestion(
                "How does `article` source its main claim about the event?",
                ATTRIBUTION_CRITERIA
        ));
        return questions;
    }

    private static List<String> truncateParagraphs(List<String> paragraphs, int maxParagraphs, int maxChars) {
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

    private static double averageProb(JevModels.ChoiceAnswer a, JevModels.ChoiceAnswer b, String option) {
        return (a.prob(option) + b.prob(option)) / 2.0;
    }

    private static LinkedHashMap<String, String> createRelevantCriteria() {
        LinkedHashMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put("true", "Reports on that exact event involving the same actors");
        criteria.put("false", "Different event, different actors, or unrelated");
        return criteria;
    }

    private static LinkedHashMap<String, String> createStanceCriteriaForward() {
        LinkedHashMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put("confirms_occurred", "States as fact that the event has already happened");
        criteria.put("planned_or_future", "Says it is expected, scheduled, or will happen, but has not yet");
        criteria.put("speculation_or_rumor", "Reports rumors, unconfirmed claims, or speculation");
        criteria.put("denies_or_not_occurred", "States the event did not happen, was denied, or was cancelled");
        return criteria;
    }

    private static LinkedHashMap<String, String> createStanceCriteriaReverse() {
        LinkedHashMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put("denies_or_not_occurred", "States the event did not happen, was denied, or was cancelled");
        criteria.put("speculation_or_rumor", "Reports rumors, unconfirmed claims, or speculation");
        criteria.put("planned_or_future", "Says it is expected, scheduled, or will happen, but has not yet");
        criteria.put("confirms_occurred", "States as fact that the event has already happened");
        return criteria;
    }

    private static LinkedHashMap<String, String> createAttributionCriteria() {
        LinkedHashMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put("official_source_cited",
                "Quotes or cites an official, government, company, or primary source");
        criteria.put("direct_reporting",
                "The journalist or news outlet reports it directly as confirmed fact");
        criteria.put("anonymous_sources",
                "Relies mainly on unnamed or anonymous sources");
        criteria.put("opinion_or_analysis",
                "Opinion, editorial, or commentary piece");
        return criteria;
    }
}
