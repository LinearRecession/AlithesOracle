package io.alithes.oracle.client.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed domain wrapper over a parsed Jev response including token telemetry and request SHA-256 audit hash.
 */
public record JevResponse(
        String model,
        Map<String, Answer> answers,
        int inputTokens,
        int outputTokens,
        String requestSha256,
        String rawResponseBody
) {
    public JevResponse {
        answers = answers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }

    public NoulAnswer noulAnswer(String key) {
        Answer answer = answers.get(key);
        if (answer instanceof NoulAnswer noul) {
            return noul;
        }
        throw new IllegalStateException("Expected NoulAnswer for key '" + key + "', got: " + answer);
    }

    public double noulProbability(String key) {
        return noulAnswer(key).noul();
    }

    public ChoiceAnswer choiceAnswer(String key) {
        Answer answer = answers.get(key);
        if (answer instanceof ChoiceAnswer choice) {
            return choice;
        }
        throw new IllegalStateException("Expected ChoiceAnswer for key '" + key + "', got: " + answer);
    }

    public String choice(String key) {
        return choiceAnswer(key).choice();
    }
}
