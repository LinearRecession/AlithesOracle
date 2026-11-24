package io.alithes.oracle.service;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.JevModels;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Evaluates whether a submitted oracle question satisfies deterministic window/length constraints
 * and Jev semantic admission criteria.
 */
@Service
public class AdmissionService {

    private static final int MIN_TEXT_LENGTH = 10;
    private static final int MAX_TEXT_LENGTH = 500;
    private static final Duration MAX_LOOKBACK = Duration.ofDays(80);
    private static final double MIN_DISCRETE_EVENT_PROB = 0.60;
    private static final double DEFAULT_MAX_AMBIGUOUS_SCORE = 0.65;
    private static final double MIN_NEWSWORTHY_SCORE = 0.50;

    private static final LinkedHashMap<String, String> QUESTION_TYPE_CRITERIA = createQuestionTypeCriteria();

    private final Clock clock;
    private final DecisionEngine decisionEngine;
    private final double maxAmbiguousScore;

    public AdmissionService(Clock clock, DecisionEngine decisionEngine) {
        this(clock, decisionEngine, DEFAULT_MAX_AMBIGUOUS_SCORE);
    }

    @Autowired
    public AdmissionService(
            Clock clock,
            DecisionEngine decisionEngine,
            @Value("${oracle.admission.max-ambiguous-score:0.65}") double maxAmbiguousScore
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
        if (!Double.isFinite(maxAmbiguousScore) || maxAmbiguousScore < 0.0 || maxAmbiguousScore > 1.0) {
            throw new IllegalArgumentException(
                    "oracle.admission.max-ambiguous-score must be in [0.0, 1.0], got " + maxAmbiguousScore);
        }
        this.maxAmbiguousScore = maxAmbiguousScore;
    }

    public record AdmissionResult(boolean accepted, List<String> reasons) {
        public AdmissionResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public static AdmissionResult accept() {
            return new AdmissionResult(true, List.of());
        }

        public static AdmissionResult rejected(List<String> reasons) {
            return new AdmissionResult(false, reasons);
        }
    }

    /**
     * Runs deterministic checks followed by a single Jev admission evaluation.
     */
    public AdmissionResult evaluate(String text, String resolutionCriteria, Instant windowStart, Instant deadline) {
        String trimmedText = text == null ? "" : text.trim();
        List<String> deterministicReasons = validateDeterministic(trimmedText, windowStart, deadline);
        if (!deterministicReasons.isEmpty()) {
            return AdmissionResult.rejected(deterministicReasons);
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", trimmedText);
        state.put("resolution_criteria", resolutionCriteria == null ? "" : resolutionCriteria.trim());

        Map<String, JevModels.QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("question_type", new JevModels.ChoiceQuestion(
                "Classify the type of event asked about in `question`.",
                QUESTION_TYPE_CRITERIA
        ));
        questions.put("is_ambiguous", new JevModels.NoulQuestion(
                "Could two reasonable readers disagree about what counts as YES for this question?"
        ));
        questions.put("is_newsworthy", new JevModels.NoulQuestion(
                "Would mainstream international or national news outlets plausibly report this event if it happened?"
        ));

        JevModels.JevResponse response = decisionEngine.evaluate(state, questions);

        JevModels.ChoiceAnswer questionType = response.choiceAnswer("question_type");
        double pDiscrete = questionType.prob("discrete_event");
        double pAmbiguous = response.noulProbability("is_ambiguous");
        double pNewsworthy = response.noulProbability("is_newsworthy");

        List<String> semanticReasons = new ArrayList<>();
        if (!"discrete_event".equals(questionType.choice())) {
            semanticReasons.add("question_type is '" + questionType.choice() + "', expected 'discrete_event'");
        }
        if (pDiscrete < MIN_DISCRETE_EVENT_PROB) {
            semanticReasons.add(String.format(
                    Locale.ROOT,
                    "discrete_event probability %.2f is below %.2f",
                    pDiscrete, MIN_DISCRETE_EVENT_PROB
            ));
        }
        if (pAmbiguous >= maxAmbiguousScore) {
            semanticReasons.add(String.format(
                    Locale.ROOT,
                    "is_ambiguous score %.2f must be below %.2f",
                    pAmbiguous, maxAmbiguousScore
            ));
        }
        if (pNewsworthy < MIN_NEWSWORTHY_SCORE) {
            semanticReasons.add(String.format(
                    Locale.ROOT,
                    "is_newsworthy score %.2f is below %.2f",
                    pNewsworthy, MIN_NEWSWORTHY_SCORE
            ));
        }

        if (!semanticReasons.isEmpty()) {
            return AdmissionResult.rejected(semanticReasons);
        }
        return AdmissionResult.accept();
    }

    private List<String> validateDeterministic(String trimmedText, Instant windowStart, Instant deadline) {
        List<String> reasons = new ArrayList<>();
        if (trimmedText.isEmpty()) {
            reasons.add("text must not be blank");
        } else if (trimmedText.length() < MIN_TEXT_LENGTH || trimmedText.length() > MAX_TEXT_LENGTH) {
            reasons.add("text length must be between 10 and 500 characters");
        }

        Instant earliestAllowed = clock.instant().minus(MAX_LOOKBACK);
        if (windowStart == null) {
            reasons.add("windowStart must not be null");
        } else if (windowStart.isBefore(earliestAllowed)) {
            reasons.add("windowStart is older than 80 days (GDELT limit)");
        }

        if (deadline == null) {
            reasons.add("deadline must not be null");
        } else if (windowStart != null && !deadline.isAfter(windowStart)) {
            reasons.add("deadline must be after windowStart");
        }
        return reasons;
    }

    private static LinkedHashMap<String, String> createQuestionTypeCriteria() {
        LinkedHashMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put("discrete_event",
                "Asks whether a specific, verifiable public event happened "
                        + "(announcement, resignation, election, law passed, deal signed, strike, sanction)");
        criteria.put("numeric_threshold",
                "Depends on an exact number, price, sports score, or statistic");
        criteria.put("subjective",
                "Depends on opinion, quality, or value judgment");
        criteria.put("unverifiable",
                "Private event, rumor-only, or cannot be checked from public news");
        return criteria;
    }
}
