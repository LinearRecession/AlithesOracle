package io.alithes.oracle.client;

import io.alithes.oracle.config.NewsProperties;
import io.alithes.oracle.config.OracleProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fast news-search adapter using Google News RSS ({@code /rss/search}).
 */
@Component
public class GoogleNewsRssClient extends AbstractRssNewsClient {

    private static final DateTimeFormatter DATE_FILTER_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

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
            NewsProperties newsProps,
            String userAgent,
            HttpClient httpClient,
            Clock clock
    ) {
        super(newsProps, Objects.requireNonNull(newsProps, "newsProps must not be null").googleRssBaseUrl(),
                userAgent, httpClient, clock);
    }

    @Override
    protected String providerName() {
        return "Google News RSS";
    }

    @Override
    protected long timeoutMs() {
        return newsProps.googleRssTimeoutMs();
    }

    @Override
    protected int maxResults() {
        return newsProps.googleRssMaxResults();
    }

    @Override
    protected URI buildSearchUri(String trimmedQuery, Instant from, Instant to) {
        String windowedQuery = trimmedQuery
                + " after:" + DATE_FILTER_FORMAT.format(from.minus(Duration.ofDays(1)))
                + " before:" + DATE_FILTER_FORMAT.format(to.plus(Duration.ofDays(1)));
        String encoded = URLEncoder.encode(windowedQuery, StandardCharsets.UTF_8);
        return URI.create(baseUrl() + "/rss/search?q=" + encoded + "&hl=en-US&gl=US&ceid=US:en");
    }

    @Override
    protected Optional<GdeltArticle> mapItem(Element item, Instant parsedPubDate) {
        String link = textOf(item, "link");
        if (link.isBlank()) {
            return Optional.empty();
        }
        Element sourceEl = item.selectFirst("source");
        String sourceUrl = sourceEl != null ? sourceEl.attr("url").trim() : "";
        String sourceText = sourceEl != null ? sourceEl.text().trim() : "";
        String domain = extractNormalizedDomain(!sourceUrl.isBlank() ? sourceUrl : link);

        String title = cleanTitle(textOf(item, "title"), sourceText);
        Instant seenDate = parsedPubDate != null ? parsedPubDate : clock.instant();
        return Optional.of(new GdeltArticle(link, title, seenDate, domain, "English", "United States"));
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
