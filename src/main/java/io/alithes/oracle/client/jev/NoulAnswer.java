package io.alithes.oracle.client.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Continuous truth probability in {@code [0.0, 1.0]} returned for a {@link NoulQuestion}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoulAnswer(double noul) implements Answer {
    public NoulAnswer {
        if (!Double.isFinite(noul) || noul < 0.0 || noul > 1.0) {
            throw new IllegalArgumentException("Noul probability must be in [0.0, 1.0], got " + noul);
        }
    }
}
