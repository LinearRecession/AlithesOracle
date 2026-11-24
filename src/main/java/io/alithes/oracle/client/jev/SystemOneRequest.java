package io.alithes.oracle.client.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire request payload for Jev {@code POST /v1/systemone}.
 */
public record SystemOneRequest(
        String model,
        Object state,
        Map<String, QuestionSpec> questions
) {
    public SystemOneRequest {
        questions = questions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(questions));
    }
}
