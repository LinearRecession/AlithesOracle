package io.alithes.oracle.client;

import java.util.Map;

/**
 * Port for evaluating structured state and typed questions (e.g. via TypeSafe Jev).
 */
public interface DecisionEngine {

    JevModels.JevResponse evaluate(Object state, Map<String, JevModels.QuestionSpec> questions);
}
