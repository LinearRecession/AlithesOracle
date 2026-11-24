package io.alithes.oracle.service;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.FetchedArticle;
import io.alithes.oracle.client.JevContextTooLargeException;
import io.alithes.oracle.client.jev.ChoiceAnswer;
import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.config.AssessorProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.service.assessment.AssessmentPromptFactory;
import io.alithes.oracle.service.assessment.ParagraphScreener;
import io.alithes.oracle.util.PromptInjectionDetector;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Evaluates fetched article evidence against a question using paragraph screening
 * and dual-order stance elicitation.
 */
@Service
public class EvidenceAssessor {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAssessor.class);

    private final Clock clock;
    private final DecisionEngine decisionEngine;
    private final AssessorProperties assessorProps;
    private final ParagraphScreener paragraphScreener;
    private final AssessmentPromptFactory promptFactory;

    @Autowired
    public EvidenceAssessor(
            Clock clock,
            DecisionEngine decisionEngine,
            OracleProperties props,
            ParagraphScreener paragraphScreener,
            AssessmentPromptFactory promptFactory
    ) {
        this(
                clock,
                decisionEngine,
                Objects.requireNonNull(props, "props must not be null").assessor(),
                paragraphScreener,
                promptFactory
        );
    }

    public EvidenceAssessor(Clock clock, DecisionEngine decisionEngine) {
        this(
                clock,
                decisionEngine,
                AssessorProperties.defaults(),
                new ParagraphScreener(decisionEngine, AssessorProperties.defaults()),
                new AssessmentPromptFactory()
        );
    }

    public EvidenceAssessor(
            Clock clock,
            DecisionEngine decisionEngine,
            AssessorProperties assessorProps,
            ParagraphScreener paragraphScreener,
            AssessmentPromptFactory promptFactory
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
        this.assessorProps = Objects.requireNonNull(assessorProps, "assessorProps must not be null");
        this.paragraphScreener = Objects.requireNonNull(paragraphScreener, "paragraphScreener must not be null");
        this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory must not be null");
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

        List<String> selectedParagraphs = paragraphScreener.selectParagraphs(q, title, fetched);
        JevResponse response = evaluateWithContextRetry(q, ev, selectedParagraphs);

        double pRelevant = response.noulProbability("relevant");
        double pStanceFits = response.noulProbability("stance_fits");
        ChoiceAnswer stanceA = response.choiceAnswer("stance_a");
        ChoiceAnswer stanceB = response.choiceAnswer("stance_b");
        double pFresh = response.noulProbability("is_recent");
        String attribution = response.choice("attribution");

        double pConfirms = averageProb(stanceA, stanceB, "confirms_occurred");
        double pPlanned = averageProb(stanceA, stanceB, "planned_or_future");
        double pSpeculation = averageProb(stanceA, stanceB, "speculation_or_rumor");
        double pDenies = averageProb(stanceA, stanceB, "denies_or_not_occurred");

        boolean stanceUnstable = !Objects.equals(stanceA.choice(), stanceB.choice())
                || Math.abs(stanceA.prob("confirms_occurred") - stanceB.prob("confirms_occurred"))
                        > assessorProps.stanceDeltaUnstableThreshold();
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

    static List<String> selectScreeningCandidates(String questionText, List<String> paragraphs) {
        return ParagraphScreener.selectCandidates(
                questionText, paragraphs, AssessorProperties.defaults().screeningMaxInputParagraphs());
    }

    private JevResponse evaluateWithContextRetry(Question q, Evidence ev, List<String> selectedParagraphs) {
        Map<String, QuestionSpec> questions = promptFactory.buildQuestions();
        try {
            return decisionEngine.evaluate(promptFactory.buildState(q, ev, selectedParagraphs), questions);
        } catch (JevContextTooLargeException tooLarge) {
            log.warn("Main assessment exceeded Jev context limit for evidence {}; retrying with truncated paragraphs",
                    ev.id());
            List<String> truncated = ParagraphScreener.truncateParagraphs(
                    selectedParagraphs, assessorProps.retryMaxParagraphs(), assessorProps.retryMaxChars());
            return decisionEngine.evaluate(promptFactory.buildState(q, ev, truncated), questions);
        }
    }

    private static double averageProb(ChoiceAnswer a, ChoiceAnswer b, String option) {
        return (a.prob(option) + b.prob(option)) / 2.0;
    }
}
