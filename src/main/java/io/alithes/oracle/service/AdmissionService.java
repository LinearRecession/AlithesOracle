package io.alithes.oracle.service;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.jev.ChoiceAnswer;
import io.alithes.oracle.client.jev.ChoiceQuestion;
import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.NoulQuestion;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.config.AdmissionProperties;
import io.alithes.oracle.config.OracleProperties;
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
import org.springframework.stereotype.Service;

/**
 * Evaluates whether a submitted oracle question satisfies deterministic window/length constraints
 * and Jev semantic admission criteria.
 */
@Service
public class AdmissionService {

    private static final LinkedHashMap<String, String> QUESTION_TYPE_CRITERIA = createQuestionTypeCriteria();

    private final Clock clock;
    private final DecisionEngine decisionEngine;
    private final AdmissionProperties admissionProps;

    @Autowired
    public AdmissionService(Clock clock, DecisionEngine decisionEngine, OracleProperties props) {
        this(clock, decisionEngine, Objects.requireNonNull(props, "props must not be null").admission());
    }

    public AdmissionService(Clock clock, DecisionEngine decisionEngine) {
        this(clock, decisionEngine, AdmissionProperties.defaults());
    }

    public AdmissionService(Clock clock, DecisionEngine decisionEngine, AdmissionProperties admissionProps) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
        this.admissionProps = Objects.requireNonNull(admissionProps, "admissionProps must not be null");
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

        Map<String, QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("question_type", new ChoiceQuestion(
                "Classify the type of event asked about in `question`.",
                QUESTION_TYPE_CRITERIA
        ));
        questions.put("is_ambiguous", new NoulQuestion(
                "Could two reasonable readers disagree about what counts as YES for this question?"
        ));
        questions.put("is_newsworthy", new NoulQuestion(
                "Would mainstream international or national news outlets plausibly report this event if it happened?"
        ));

        JevResponse response = decisionEngine.evaluate(state, questions);

        ChoiceAnswer questionType = response.choiceAnswer("question_type");
        double pDiscrete = questionType.prob("discrete_event");
        double pAmbiguous = response.noulProbability("is_ambiguous");
        double pNewsworthy = response.noulProbability("is_newsworthy");

        List<String> semanticReasons = new ArrayList<>();
        if (!"discrete_event".equals(questionType.choice())) {
            semanticReasons.add("question_type is '" + questionType.choice() + "', expected 'discrete_event'");
        }
        if (pDiscrete < admissionProps.minDiscreteEventProb()) {
            semanticReasons.add(String.format(
                    Locale.ROOT,
                    "discrete_event probability %.2f is below %.2f",
                    pDiscrete, admissionProps.minDiscreteEventProb()
            ));
        }
        if (pAmbiguous >= admissionProps.maxAmbiguousScore()) {
            semanticReasons.add(String.format(
                    Locale.ROOT,
                    "is_ambiguous score %.2f must be below %.2f",
                    pAmbiguous, admissionProps.maxAmbiguousScore()
            ));
        }
        if (pNewsworthy < admissionProps.minNewsworthyScore()) {
            semanticReasons.add(String.format(
                    Locale.ROOT,
                    "is_newsworthy score %.2f is below %.2f",
                    pNewsworthy, admissionProps.minNewsworthyScore()
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
        } else if (trimmedText.length() < admissionProps.minTextLength()
                || trimmedText.length() > admissionProps.maxTextLength()) {
            reasons.add("text length must be between "
                    + admissionProps.minTextLength() + " and " + admissionProps.maxTextLength() + " characters");
        }

        Instant earliestAllowed = clock.instant().minus(Duration.ofDays(admissionProps.maxLookbackDays()));
        if (windowStart == null) {
            reasons.add("windowStart must not be null");
        } else if (windowStart.isBefore(earliestAllowed)) {
            reasons.add("windowStart is older than " + admissionProps.maxLookbackDays() + " days (GDELT limit)");
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
