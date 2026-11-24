package io.alithes.oracle.config;

import java.util.Set;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for RSS fallback news providers and targeted Wikipedia sourcing ({@code oracle.news.*}).
 */
public record NewsProperties(
        @DefaultValue("true") boolean bingRssEnabled,
        @DefaultValue("https://www.bing.com") String bingRssBaseUrl,
        @DefaultValue("8000") long bingRssTimeoutMs,
        @DefaultValue("30") int bingRssMaxResults,
        @DefaultValue("true") boolean googleRssEnabled,
        @DefaultValue("https://news.google.com") String googleRssBaseUrl,
        @DefaultValue("8000") long googleRssTimeoutMs,
        @DefaultValue("30") int googleRssMaxResults,
        @DefaultValue("24") long rssDatePaddingHours,
        @DefaultValue("60000") long gdeltCooldownMs,
        @DefaultValue("true") boolean wikipediaEnabled,
        @DefaultValue("https://en.wikipedia.org") String wikipediaBaseUrl,
        @DefaultValue("6000") long wikipediaTimeoutMs,
        @DefaultValue("3") int wikipediaMaxPages,
        @DefaultValue("5") int wikipediaMaxCitations,
        @DefaultValue("160") int wikipediaMaxCitationTitleChars,
        @DefaultValue("2") int wikipediaMaxRedirects,
        @DefaultValue({
                "wikipedia.org", "wikimedia.org", "wikidata.org",
                "twitter.com", "x.com", "facebook.com", "instagram.com",
                "youtube.com", "tiktok.com", "linkedin.com",
                "doi.org", "worldcat.org", "isbn",
                "web.archive.org", "archive.is", "archive.ph", "archive.today"
        }) Set<String> wikipediaSkippedDomains
) {
    public static final Set<String> DEFAULT_SKIPPED_DOMAINS = Set.of(
            "wikipedia.org", "wikimedia.org", "wikidata.org",
            "twitter.com", "x.com", "facebook.com", "instagram.com",
            "youtube.com", "tiktok.com", "linkedin.com",
            "doi.org", "worldcat.org", "isbn",
            "web.archive.org", "archive.is", "archive.ph", "archive.today"
    );

    @ConstructorBinding
    public NewsProperties {
        PropertyAssertions.requirePositive(bingRssTimeoutMs, "oracle.news.bing-rss-timeout-ms");
        PropertyAssertions.requirePositive(bingRssMaxResults, "oracle.news.bing-rss-max-results");
        PropertyAssertions.requirePositive(googleRssTimeoutMs, "oracle.news.google-rss-timeout-ms");
        PropertyAssertions.requirePositive(googleRssMaxResults, "oracle.news.google-rss-max-results");
        PropertyAssertions.requirePositive(rssDatePaddingHours, "oracle.news.rss-date-padding-hours");
        PropertyAssertions.requireNonNegative(gdeltCooldownMs, "oracle.news.gdelt-cooldown-ms");
        PropertyAssertions.requirePositive(wikipediaTimeoutMs, "oracle.news.wikipedia-timeout-ms");
        PropertyAssertions.requirePositive(wikipediaMaxPages, "oracle.news.wikipedia-max-pages");
        PropertyAssertions.requireNonNegative(wikipediaMaxCitations, "oracle.news.wikipedia-max-citations");
        PropertyAssertions.requirePositive(
                wikipediaMaxCitationTitleChars, "oracle.news.wikipedia-max-citation-title-chars");
        PropertyAssertions.requireNonNegative(wikipediaMaxRedirects, "oracle.news.wikipedia-max-redirects");
        wikipediaSkippedDomains = wikipediaSkippedDomains == null
                ? DEFAULT_SKIPPED_DOMAINS
                : Set.copyOf(wikipediaSkippedDomains);
    }

    public NewsProperties(
            boolean bingRssEnabled,
            String bingRssBaseUrl,
            long bingRssTimeoutMs,
            int bingRssMaxResults,
            boolean googleRssEnabled,
            String googleRssBaseUrl,
            long googleRssTimeoutMs,
            int googleRssMaxResults,
            long gdeltCooldownMs,
            boolean wikipediaEnabled,
            String wikipediaBaseUrl,
            long wikipediaTimeoutMs,
            int wikipediaMaxPages,
            int wikipediaMaxCitations
    ) {
        this(
                bingRssEnabled, bingRssBaseUrl, bingRssTimeoutMs, bingRssMaxResults,
                googleRssEnabled, googleRssBaseUrl, googleRssTimeoutMs, googleRssMaxResults,
                24L, gdeltCooldownMs, wikipediaEnabled, wikipediaBaseUrl, wikipediaTimeoutMs,
                wikipediaMaxPages, wikipediaMaxCitations, 160, 2, DEFAULT_SKIPPED_DOMAINS
        );
    }

    public static NewsProperties defaults() {
        return new NewsProperties(
                true, "https://www.bing.com", 8_000L, 30,
                true, "https://news.google.com", 8_000L, 30,
                24L, 60_000L, true, "https://en.wikipedia.org", 6_000L,
                3, 5, 160, 2, DEFAULT_SKIPPED_DOMAINS
        );
    }
}
