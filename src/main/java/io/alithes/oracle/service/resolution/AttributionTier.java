package io.alithes.oracle.service.resolution;

import java.util.Locale;

/**
 * Credibility weights associated with Jev-classified article attribution styles.
 */
public enum AttributionTier {
    OFFICIAL_SOURCE_CITED("official_source_cited", 1.0),
    DIRECT_REPORTING("direct_reporting", 0.9),
    ANONYMOUS_SOURCES("anonymous_sources", 0.5),
    OPINION_OR_ANALYSIS("opinion_or_analysis", 0.2),
    DEFAULT("", 0.5);

    private final String key;
    private final double weight;

    AttributionTier(String key, double weight) {
        this.key = key;
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    public static AttributionTier fromKey(String rawAttribution) {
        if (rawAttribution == null || rawAttribution.isBlank()) {
            return DEFAULT;
        }
        String normalized = rawAttribution.trim().toLowerCase(Locale.ROOT);
        for (AttributionTier tier : values()) {
            if (!tier.key.isEmpty() && tier.key.equals(normalized)) {
                return tier;
            }
        }
        return DEFAULT;
    }
}
