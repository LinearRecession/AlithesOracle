package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for article HTML fetching and extraction ({@code oracle.fetcher.*}).
 */
public record FetcherProperties(
        @DefaultValue("10000") long timeoutMs,
        @DefaultValue("2097152") int maxBytes,
        @DefaultValue("AlithesOracle/1.0 (+https://alithes.io)") String userAgent,
        @DefaultValue("40") int minParagraphChars,
        @DefaultValue("300") int minExtractedChars,
        @DefaultValue("2000") int maxExcerptChars,
        @DefaultValue("3") int maxRedirects,
        @DefaultValue("8192") int streamBufferBytes
) {
    @ConstructorBinding
    public FetcherProperties {
        PropertyAssertions.requirePositive(timeoutMs, "oracle.fetcher.timeout-ms");
        PropertyAssertions.requirePositive(maxBytes, "oracle.fetcher.max-bytes");
        PropertyAssertions.requirePositive(minParagraphChars, "oracle.fetcher.min-paragraph-chars");
        PropertyAssertions.requirePositive(minExtractedChars, "oracle.fetcher.min-extracted-chars");
        PropertyAssertions.requirePositive(maxExcerptChars, "oracle.fetcher.max-excerpt-chars");
        PropertyAssertions.requireNonNegative(maxRedirects, "oracle.fetcher.max-redirects");
        PropertyAssertions.requirePositive(streamBufferBytes, "oracle.fetcher.stream-buffer-bytes");
    }

    public FetcherProperties(long timeoutMs, int maxBytes, String userAgent) {
        this(timeoutMs, maxBytes, userAgent, 40, 300, 2_000, 3, 8_192);
    }

    public static FetcherProperties defaults() {
        return new FetcherProperties(
                10_000L,
                2_097_152,
                "AlithesOracle/1.0 (+https://alithes.io)",
                40,
                300,
                2_000,
                3,
                8_192
        );
    }
}
