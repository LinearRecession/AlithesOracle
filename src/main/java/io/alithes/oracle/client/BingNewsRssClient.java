package io.alithes.oracle.client;

import io.alithes.oracle.config.OracleProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
import java.util.Locale;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * News-search adapter using Bing News RSS ({@code /news/search?format=rss}),
 * extracting direct publisher URLs from Bing redirect links so full article bodies can be fetched.
 */
@Component
public class BingNewsRssClient implements NewsSource {

    private static final Duration DATE_PADDING = Duration.ofHours(24);

    private final OracleProperties.News newsProps;
    private final String userAgent;
    private final HttpClient httpClient;
    private final Clock clock;
    private final String baseUrl;

    @Autowired
    public BingNewsRssClient(OracleProperties props, HttpClient httpClient, Clock clock) {
        this(
                Objects.requireNonNull(props, "props must not be null").news(),
                props.fetcher().userAgent(),
                httpClient,
                clock
        );
    }

    public BingNewsRssClient(
            OracleProperties.News newsProps,
            String userAgent,
            HttpClient httpClient,
            Clock clock
    ) {
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "AlithesOracle/1.0" : userAgent;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        String rawBaseUrl = Objects.requireNonNull(newsProps.bingRssBaseUrl(), "bingRssBaseUrl must not be null");
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

        URI uri = buildSearchUri(query);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(newsProps.bingRssTimeoutMs()))
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
                throw new GdeltException("Bing News RSS returned HTTP " + status, status, body);
            }
            return parseRss(body, from, to);
        } catch (IOException e) {
            throw new GdeltException("Bing News RSS request failed: " + e.getMessage(), -1, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GdeltException("Bing News RSS request interrupted", -1, null, e);
        }
    }

    private URI buildSearchUri(String query) {
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        return URI.create(baseUrl + "/news/search?q=" + encoded + "&format=rss&mkt=en-US&setlang=en");
    }

    private List<GdeltArticle> parseRss(String xml, Instant from, Instant to) {
        if (xml.isBlank()) {
            return List.of();
        }
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        Instant lowerBound = from.minus(DATE_PADDING);
        Instant upperBound = to.plus(DATE_PADDING);

        List<GdeltArticle> results = new ArrayList<>();
        int maxResults = Math.max(1, newsProps.bingRssMaxResults());

        for (Element item : doc.select("item")) {
            if (results.size() >= maxResults) {
                break;
            }
            String rawLink = textOf(item, "link");
            if (rawLink.isBlank()) {
                continue;
            }
            String directUrl = extractDirectUrl(rawLink);
            if (directUrl.isBlank()) {
                continue;
            }

            Instant parsedPubDate = parsePubDate(textOf(item, "pubDate"));
            if (parsedPubDate != null && (parsedPubDate.isBefore(lowerBound) || parsedPubDate.isAfter(upperBound))) {
                continue;
            }

            String domain = extractDomain(directUrl);
            String title = textOf(item, "title");
            Instant seenDate = parsedPubDate != null ? parsedPubDate : clock.instant();

            results.add(new GdeltArticle(
                    directUrl,
                    title,
                    seenDate,
                    domain,
                    "English",
                    "United States"
            ));
        }

        return List.copyOf(results);
    }

    static String extractDirectUrl(String rawLink) {
        try {
            URI uri = URI.create(rawLink.trim());
            String query = uri.getRawQuery();
            if (query != null && !query.isBlank()) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = pair.substring(0, eq);
                    if ("url".equalsIgnoreCase(key)) {
                        String decoded = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
                        if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                            return decoded;
                        }
                    }
                }
            }
            return rawLink.trim();
        } catch (IllegalArgumentException ignored) {
            return rawLink.trim();
        }
    }

    private static String extractDomain(String url) {
        try {
            String host = URI.create(url).getHost();
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
}
