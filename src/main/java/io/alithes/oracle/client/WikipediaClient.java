package io.alithes.oracle.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.alithes.oracle.config.FetcherProperties;
import io.alithes.oracle.config.NewsProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.util.QuestionTextAnalyzer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Targeted MediaWiki search client implementing {@link SupplementalEvidenceSource} that surfaces
 * up to {@code wikipediaMaxPages} articles without crawling and delegates footnote harvesting
 * to {@link WikipediaCitationHarvester}.
 */
@Component
public class WikipediaClient implements SupplementalEvidenceSource {

    private static final Logger log = LoggerFactory.getLogger(WikipediaClient.class);

    private final NewsProperties newsProps;
    private final String userAgent;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private final String baseUrl;
    private final String domain;
    private final WikipediaCitationHarvester citationHarvester;

    @Autowired
    public WikipediaClient(
            OracleProperties props,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock,
            WikipediaCitationHarvester citationHarvester
    ) {
        this(
                Objects.requireNonNull(props, "props must not be null").news(),
                props.fetcher().userAgent(),
                objectMapper,
                httpClient,
                clock,
                citationHarvester
        );
    }

    public WikipediaClient(
            NewsProperties newsProps,
            String userAgent,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock
    ) {
        this(
                newsProps,
                userAgent,
                objectMapper,
                httpClient,
                clock,
                new WikipediaCitationHarvester(
                        newsProps,
                        FetcherProperties.defaults().maxBytes(),
                        FetcherProperties.defaults().streamBufferBytes(),
                        userAgent,
                        httpClient,
                        clock
                )
        );
    }

    public WikipediaClient(
            NewsProperties newsProps,
            String userAgent,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock,
            WikipediaCitationHarvester citationHarvester
    ) {
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? FetcherProperties.defaults().userAgent()
                : userAgent;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.citationHarvester = Objects.requireNonNull(citationHarvester, "citationHarvester must not be null");
        String rawBaseUrl = Objects.requireNonNull(newsProps.wikipediaBaseUrl(), "wikipediaBaseUrl must not be null");
        this.baseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1) : rawBaseUrl;
        this.domain = extractHost(this.baseUrl);
    }

    /**
     * Collects targeted Wikipedia articles and external citations from matching footnotes.
     */
    @Override
    public List<GdeltArticle> collect(String questionText) {
        if (!newsProps.wikipediaEnabled() || questionText == null || questionText.isBlank()) {
            return List.of();
        }
        List<GdeltArticle> pages = searchQuestion(questionText);
        if (pages.isEmpty() || newsProps.wikipediaMaxCitations() <= 0) {
            return pages;
        }
        List<GdeltArticle> combined = new ArrayList<>(pages);
        try {
            combined.addAll(harvestCitations(questionText, pages));
        } catch (Exception e) {
            log.warn("Wikipedia citation harvest failed: {}", e.getMessage());
        }
        return List.copyOf(combined);
    }

    /**
     * Searches Wikipedia using at most two targeted queries (topic + primary entity) and returns
     * up to {@code wikipediaMaxPages} canonical article references.
     */
    public List<GdeltArticle> searchQuestion(String questionText) {
        if (!newsProps.wikipediaEnabled() || questionText == null || questionText.isBlank()) {
            return List.of();
        }

        int maxPages = Math.max(1, newsProps.wikipediaMaxPages());
        List<String> entities = QuestionTextAnalyzer.extractEntities(questionText);
        List<String> distinctiveTerms = QuestionTextAnalyzer.extractDistinctiveTerms(questionText);

        String topicQuery = buildTopicQuery(entities, distinctiveTerms);
        String primaryEntity = entities.isEmpty() ? "" : entities.get(0).trim();

        Map<String, GdeltArticle> byTitle = new LinkedHashMap<>();
        if (!topicQuery.isBlank()) {
            for (GdeltArticle hit : executeSearch(topicQuery, maxPages)) {
                byTitle.putIfAbsent(hit.title(), hit);
            }
        }
        if (!primaryEntity.isBlank() && !primaryEntity.equalsIgnoreCase(topicQuery) && byTitle.size() < maxPages) {
            for (GdeltArticle hit : executeSearch(primaryEntity, 1)) {
                byTitle.putIfAbsent(hit.title(), hit);
            }
        }

        if (byTitle.size() <= maxPages) {
            return List.copyOf(byTitle.values());
        }
        return byTitle.values().stream().limit(maxPages).toList();
    }

    /**
     * Harvests distinct-domain external news citations attached to question-matching paragraphs.
     */
    public List<GdeltArticle> harvestCitations(String questionText, List<GdeltArticle> wikiPages) {
        return citationHarvester.harvest(questionText, wikiPages);
    }

    private List<GdeltArticle> executeSearch(String query, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/w/api.php?action=query&list=search&srsearch="
                + encoded + "&srlimit=" + limit + "&format=json");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(newsProps.wikipediaTimeoutMs()))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new GdeltException(
                        "Wikipedia API returned HTTP " + response.statusCode(),
                        response.statusCode(),
                        response.body());
            }
            WikiApiResponse parsed = objectMapper.readValue(response.body(), WikiApiResponse.class);
            if (parsed == null || parsed.query() == null || parsed.query().search() == null) {
                return List.of();
            }

            List<GdeltArticle> articles = new ArrayList<>();
            for (WikiSearchHit hit : parsed.query().search()) {
                if (hit == null || hit.title() == null || hit.title().isBlank()) {
                    continue;
                }
                String title = hit.title().trim();
                articles.add(new GdeltArticle(
                        buildArticleUrl(title),
                        title,
                        parseTimestamp(hit.timestamp()),
                        domain,
                        "English",
                        "International"
                ));
            }
            return List.copyOf(articles);
        } catch (IOException e) {
            throw new GdeltException("Wikipedia search failed: " + e.getMessage(), -1, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GdeltException("Wikipedia search interrupted", -1, null, e);
        }
    }

    private String buildArticleUrl(String title) {
        String underscored = title.replace(' ', '_');
        String encoded = URLEncoder.encode(underscored, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
        return baseUrl + "/wiki/" + encoded;
    }

    private static String buildTopicQuery(List<String> entities, List<String> distinctiveTerms) {
        List<String> parts = new ArrayList<>();
        int nonEntityCount = 0;
        for (String term : distinctiveTerms) {
            if (entities.contains(term)) {
                parts.add(term);
            } else if (nonEntityCount < 5) {
                parts.add(term);
                nonEntityCount++;
            }
        }
        return String.join(" ", parts).trim();
    }

    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return clock.instant();
        }
        try {
            return Instant.parse(timestamp.trim());
        } catch (DateTimeParseException ignored) {
            return clock.instant();
        }
    }

    private static String extractHost(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return (host == null || host.isBlank()) ? "en.wikipedia.org" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "en.wikipedia.org";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WikiApiResponse(WikiQuery query) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WikiQuery(List<WikiSearchHit> search) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WikiSearchHit(String title, String timestamp) {
    }
}
