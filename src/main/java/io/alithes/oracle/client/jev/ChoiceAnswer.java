package io.alithes.oracle.client.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Classification result with calibrated option probabilities returned for a {@link ChoiceQuestion}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChoiceAnswer(
        String choice,
        double confidence,
        Map<String, Double> probabilities
) implements Answer {
    public ChoiceAnswer {
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Choice confidence must be in [0.0, 1.0], got " + confidence);
        }
        if (probabilities == null) {
            probabilities = Map.of();
        } else {
            LinkedHashMap<String, Double> validated = new LinkedHashMap<>(probabilities.size());
            for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
                Double val = entry.getValue();
                if (val == null || !Double.isFinite(val) || val < 0.0 || val > 1.0) {
                    throw new IllegalArgumentException(
                            "Choice probability for '" + entry.getKey() + "' must be in [0.0, 1.0], got " + val);
                }
                validated.put(entry.getKey(), val);
            }
            probabilities = Collections.unmodifiableMap(validated);
        }
    }

    public double prob(String option) {
        return probabilities.getOrDefault(option, 0.0);
    }
}
