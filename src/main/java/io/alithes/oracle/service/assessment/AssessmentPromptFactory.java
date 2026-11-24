package io.alithes.oracle.service.assessment;

import io.alithes.oracle.client.jev.ChoiceQuestion;
import io.alithes.oracle.client.jev.NoulQuestion;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.Question;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Constructs structured Jev {@code /v1/systemone} prompts for dual-order stance elicitation.
 */
@Component
public class AssessmentPromptFactory {

    private static final LinkedHashMap<String, String> RELEVANT_CRITERIA = createRelevantCriteria();
    private static final LinkedHashMap<String, String> STANCE_CRITERIA_FORWARD = createStanceCriteriaForward();
    private static final LinkedHashMap<String, String> STANCE_CRITERIA_REVERSE = createStanceCriteriaReverse();
    private static final LinkedHashMap<String, String> ATTRIBUTION_CRITERIA = createAttributionCriteria();

    public Map<String, Object> buildState(Question q, Evidence ev, List<String> paragraphs) {
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

    public Map<String, QuestionSpec> buildQuestions() {
        Map<String, QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("relevant", new NoulQuestion(
                "Is `article` about the specific event described in `question`?",
                RELEVANT_CRITERIA
        ));
        questions.put("stance_fits", new NoulQuestion(
                "Does `article` state or imply anything about whether the event in `question` has happened or not?"
        ));
        questions.put("stance_a", new ChoiceQuestion(
                "What does `article` report about the event in `question`?",
                STANCE_CRITERIA_FORWARD
        ));
        questions.put("stance_b", new ChoiceQuestion(
                "What does `article` report about the event in `question`?",
                STANCE_CRITERIA_REVERSE
        ));
        questions.put("is_recent", new NoulQuestion(
                "Does `article` report the event as newly occurring around the time the article was published, "
                        + "rather than recounting an older historical event from months or years ago?"
        ));
        questions.put("attribution", new ChoiceQuestion(
                "How does `article` source its main claim about the event?",
                ATTRIBUTION_CRITERIA
        ));
        return questions;
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
