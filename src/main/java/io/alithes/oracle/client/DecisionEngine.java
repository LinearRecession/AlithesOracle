package io.alithes.oracle.client;

import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.QuestionSpec;
import java.util.Map;

/**
 * Abstraction over decision-engine evaluations (backed by {@link JevClient} in production).
 */
public interface DecisionEngine {

    JevResponse evaluate(Object state, Map<String, QuestionSpec> questions);
}
