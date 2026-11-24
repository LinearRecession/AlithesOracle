package io.alithes.oracle.client;

import io.alithes.oracle.config.NewsProperties;
import io.alithes.oracle.util.UrlNormalizer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * Template Method base class for RSS-backed {@link NewsSource} adapters handling HTTP transport,
 * XML parsing, date-window filtering, and result capping.
 */
public abstract class AbstractRssNewsClient implements NewsSource {

    protected final NewsProperties newsProps;
    protected final Clock clock;
    private final String userAgent;
    private final HttpClient httpClient;
    private final String baseUrl;

    protected AbstractRssNewsClient(
            NewsProperties newsProps,
            String baseUrl,
            String userAgent,
            HttpClient httpClient,
            Clock clock
    ) {
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        String rawBaseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.baseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1) : rawBaseUrl;
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "AlithesOracle/1.0" : userAgent;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public final List<GdeltArticle> search(String query, Instant from, Instant to) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("News query must not be blank");
        }
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.isAfter(to)) {
            return List.of();
        }

        URI uri = buildSearchUri(query.trim(), from, to);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs()))
                .header("User-Agent", userAgent)
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status != 200) {
                throw new GdeltException(providerName() + " returned HTTP " + status, status, body);
            }
            return parseRss(body, from, to);
        } catch (IOException e) {
            throw new GdeltException(providerName() + " request failed: " + e.getMessage(), -1, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GdeltException(providerName() + " request interrupted", -1, null, e);
        }
    }

    protected final String baseUrl() {
        return baseUrl;
    }

    protected abstract String providerName();

    protected abstract long timeoutMs();

    protected abstract int maxResults();

    protected abstract URI buildSearchUri(String trimmedQuery, Instant from, Instant to);

    protected abstract Optional<GdeltArticle> mapItem(Element item, Instant parsedPubDate);

    private List<GdeltArticle> parseRss(String xml, Instant from, Instant to) {
        if (xml.isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        Duration padding = Duration.ofHours(newsProps.rssDatePaddingHours());
        Instant lowerBound = from.minus(padding);
        Instant upperBound = to.plus(padding);

        int limit = Math.max(1, maxResults());
        List<GdeltArticle> results = new ArrayList<>();

        for (Element item : doc.select("item")) {
            if (results.size() >= limit) {
                break;
            }
            Instant parsedPubDate = parsePubDate(textOf(item, "pubDate"));
            if (parsedPubDate != null && (parsedPubDate.isBefore(lowerBound) || parsedPubDate.isAfter(upperBound))) {
                continue;
            }
            mapItem(item, parsedPubDate).ifPresent(results::add);
        }
        return List.copyOf(results);
    }

    protected static String textOf(Element parent, String tag) {
        Element el = parent.selectFirst(tag);
        return el == null ? "" : el.text().trim();
    }

    protected static Instant parsePubDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(raw.trim());
            } catch (DateTimeParseException ignoredToo) {
                return null;
            }
        }
    }

    protected static String extractNormalizedDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return UrlNormalizer.stripHostPrefixes(host);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
