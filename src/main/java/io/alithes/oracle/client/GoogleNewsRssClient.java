package io.alithes.oracle.client;

import io.alithes.oracle.config.OracleProperties;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fast news-search adapter using Google News RSS ({@code /rss/search}).
 */
@Component
public class GoogleNewsRssClient implements NewsSource {

    private static final DateTimeFormatter DATE_FILTER_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    private static final Duration DATE_PADDING = Duration.ofHours(24);

    private final OracleProperties.News newsProps;
    private final String userAgent;
    private final HttpClient httpClient;
    private final Clock clock;
    private final String baseUrl;

    @Autowired
    public GoogleNewsRssClient(OracleProperties props, HttpClient httpClient, Clock clock) {
        this(
                Objects.requireNonNull(props, "props must not be null").news(),
                props.fetcher().userAgent(),
                httpClient,
                clock
        );
    }

    public GoogleNewsRssClient(
            OracleProperties.News newsProps,
            String userAgent,
            HttpClient httpClient,
            Clock clock
    ) {
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "AlithesOracle/1.0" : userAgent;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        String rawBaseUrl = Objects.requireNonNull(newsProps.googleRssBaseUrl(), "googleRssBaseUrl must not be null");
        this.baseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1) : rawBaseUrl;
    }

    @Override
    public List<GdeltArticle> search(String query, Instant from, Instant to) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("News query must not be blank");
        }
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.isAfter(to)) {
            return List.of();
        }

        URI uri = buildSearchUri(query, from, to);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(newsProps.googleRssTimeoutMs()))
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
                throw new GdeltException("Google News RSS returned HTTP " + status, status, body);
            }
            return parseRss(body, from, to);
        } catch (IOException e) {
            throw new GdeltException("Google News RSS request failed: " + e.getMessage(), -1, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GdeltException("Google News RSS request interrupted", -1, null, e);
        }
    }

    private URI buildSearchUri(String query, Instant from, Instant to) {
        String windowedQuery = query.trim()
                + " after:" + DATE_FILTER_FORMAT.format(from.minus(Duration.ofDays(1)))
                + " before:" + DATE_FILTER_FORMAT.format(to.plus(Duration.ofDays(1)));
        String encoded = URLEncoder.encode(windowedQuery, StandardCharsets.UTF_8);
        return URI.create(baseUrl + "/rss/search?q=" + encoded + "&hl=en-US&gl=US&ceid=US:en");
    }

    private List<GdeltArticle> parseRss(String xml, Instant from, Instant to) {
        if (xml.isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        Instant lowerBound = from.minus(DATE_PADDING);
        Instant upperBound = to.plus(DATE_PADDING);

        List<GdeltArticle> results = new ArrayList<>();
        int maxResults = Math.max(1, newsProps.googleRssMaxResults());

        for (Element item : doc.select("item")) {
            if (results.size() >= maxResults) {
                break;
            }
            String link = textOf(item, "link");
            if (link.isBlank()) {
                continue;
            }

            String rawPubDate = textOf(item, "pubDate");
            Instant parsedPubDate = parsePubDate(rawPubDate);
            if (parsedPubDate != null && (parsedPubDate.isBefore(lowerBound) || parsedPubDate.isAfter(upperBound))) {
                continue;
            }

            Element sourceEl = item.selectFirst("source");
            String sourceUrl = sourceEl != null ? sourceEl.attr("url").trim() : "";
            String sourceText = sourceEl != null ? sourceEl.text().trim() : "";
            String domain = resolvePublisherDomain(sourceUrl, link);

            String title = cleanTitle(textOf(item, "title"), sourceText);
            Instant seenDate = parsedPubDate != null ? parsedPubDate : clock.instant();

            results.add(new GdeltArticle(
                    link,
                    title,
                    seenDate,
                    domain,
                    "English",
                    "United States"
            ));
        }

        return List.copyOf(results);
    }

    private static String textOf(Element parent, String tag) {
        Element el = parent.selectFirst(tag);
        return el == null ? "" : el.text().trim();
    }

    private static Instant parsePubDate(String raw) {
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

    private static String resolvePublisherDomain(String sourceUrl, String fallbackLink) {
        String candidate = !sourceUrl.isBlank() ? sourceUrl : fallbackLink;
        try {
            String host = URI.create(candidate).getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.startsWith("www.")) {
                lower = lower.substring(4);
            } else if (lower.startsWith("m.")) {
                lower = lower.substring(2);
            }
            return lower;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String cleanTitle(String rawTitle, String sourceText) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "";
        }
        if (!sourceText.isBlank()) {
            String suffix = " - " + sourceText;
            if (rawTitle.endsWith(suffix) && rawTitle.length() > suffix.length()) {
                return rawTitle.substring(0, rawTitle.length() - suffix.length()).trim();
            }
        }
        return rawTitle;
    }
}
