package io.alithes.oracle.service.assessment;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.NoulQuestion;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.Question;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Scores newly discovered article headlines for event relevance before fetching full HTML bodies.
 */
@Component
public class HeadlinePrefilter {

    private final DecisionEngine decisionEngine;

    public HeadlinePrefilter(DecisionEngine decisionEngine) {
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
    }

    public double score(Question question, Evidence evidence) {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", question.text());
        state.put("headline", evidence.title() == null ? "" : evidence.title());
        state.put("domain", evidence.domain() == null ? "" : evidence.domain());

        Map<String, QuestionSpec> questions = Map.of(
                "relevant",
                new NoulQuestion("Could an article with this headline be about the event in question?")
        );
        JevResponse response = decisionEngine.evaluate(state, questions);
        return response.noulProbability("relevant");
    }
}
