package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for natural-language to GDELT query compilation ({@code oracle.query.*}).
 */
public record QueryProperties(
        @DefaultValue("3") int maxOverrideQueries,
        @DefaultValue("2") int maxCompiledQueries,
        @DefaultValue("0.25") double runnerUpMinProbability
) {
    public QueryProperties {
        PropertyAssertions.requirePositive(maxOverrideQueries, "oracle.query.max-override-queries");
        PropertyAssertions.requirePositive(maxCompiledQueries, "oracle.query.max-compiled-queries");
        PropertyAssertions.requireProbability(runnerUpMinProbability, "oracle.query.runner-up-min-probability");
    }

    public static QueryProperties defaults() {
        return new QueryProperties(3, 2, 0.25);
    }
}
