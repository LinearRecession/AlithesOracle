package io.alithes.oracle.client;

import io.alithes.oracle.config.NewsProperties;
import io.alithes.oracle.config.OracleProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * News-search adapter using Bing News RSS ({@code /news/search?format=rss}),
 * decoding redirect links to direct publisher URLs so full article bodies can be fetched.
 */
@Component
public class BingNewsRssClient extends AbstractRssNewsClient {

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
            NewsProperties newsProps,
            String userAgent,
            HttpClient httpClient,
            Clock clock
    ) {
        super(newsProps, Objects.requireNonNull(newsProps, "newsProps must not be null").bingRssBaseUrl(),
                userAgent, httpClient, clock);
    }

    @Override
    protected String providerName() {
        return "Bing News RSS";
    }

    @Override
    protected long timeoutMs() {
        return newsProps.bingRssTimeoutMs();
    }

    @Override
    protected int maxResults() {
        return newsProps.bingRssMaxResults();
    }

    @Override
    protected URI buildSearchUri(String trimmedQuery, Instant from, Instant to) {
        String encoded = URLEncoder.encode(trimmedQuery, StandardCharsets.UTF_8);
        return URI.create(baseUrl() + "/news/search?q=" + encoded + "&format=rss&mkt=en-US&setlang=en");
    }

    @Override
    protected Optional<GdeltArticle> mapItem(Element item, Instant parsedPubDate) {
        String rawLink = textOf(item, "link");
        if (rawLink.isBlank()) {
            return Optional.empty();
        }
        String directUrl = extractDirectUrl(rawLink);
        if (directUrl.isBlank()) {
            return Optional.empty();
        }
        String domain = extractNormalizedDomain(directUrl);
        String title = textOf(item, "title");
        Instant seenDate = parsedPubDate != null ? parsedPubDate : clock.instant();
        return Optional.of(new GdeltArticle(directUrl, title, seenDate, domain, "English", "United States"));
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
                    if ("url".equalsIgnoreCase(pair.substring(0, eq))) {
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
}
