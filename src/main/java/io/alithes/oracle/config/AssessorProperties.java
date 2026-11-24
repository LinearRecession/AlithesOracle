package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for article paragraph screening, context truncation, and stance assessment ({@code oracle.assessor.*}).
 */
public record AssessorProperties(
        @DefaultValue("8") int shortArticleMaxParagraphs,
        @DefaultValue("4000") int shortArticleMaxChars,
        @DefaultValue("25") int screeningMaxInputParagraphs,
        @DefaultValue("0.45") double screeningMinProbability,
        @DefaultValue("8") int screeningMaxSelectedParagraphs,
        @DefaultValue("6000") int screeningMaxSelectedChars,
        @DefaultValue("3") int retryMaxParagraphs,
        @DefaultValue("1500") int retryMaxChars,
        @DefaultValue("0.25") double stanceDeltaUnstableThreshold
) {
    public AssessorProperties {
        PropertyAssertions.requirePositive(shortArticleMaxParagraphs, "oracle.assessor.short-article-max-paragraphs");
        PropertyAssertions.requirePositive(shortArticleMaxChars, "oracle.assessor.short-article-max-chars");
        PropertyAssertions.requirePositive(
                screeningMaxInputParagraphs, "oracle.assessor.screening-max-input-paragraphs");
        PropertyAssertions.requireProbability(screeningMinProbability, "oracle.assessor.screening-min-probability");
        PropertyAssertions.requirePositive(
                screeningMaxSelectedParagraphs, "oracle.assessor.screening-max-selected-paragraphs");
        PropertyAssertions.requirePositive(
                screeningMaxSelectedChars, "oracle.assessor.screening-max-selected-chars");
        PropertyAssertions.requirePositive(retryMaxParagraphs, "oracle.assessor.retry-max-paragraphs");
        PropertyAssertions.requirePositive(retryMaxChars, "oracle.assessor.retry-max-chars");
        PropertyAssertions.requireProbability(
                stanceDeltaUnstableThreshold, "oracle.assessor.stance-delta-unstable-threshold");
    }

    public static AssessorProperties defaults() {
        return new AssessorProperties(8, 4_000, 25, 0.45, 8, 6_000, 3, 1_500, 0.25);
    }
}
