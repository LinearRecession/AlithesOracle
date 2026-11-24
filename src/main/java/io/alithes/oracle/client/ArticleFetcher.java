package io.alithes.oracle.client;

import io.alithes.oracle.config.FetcherProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.util.BoundedStreamReader;
import io.alithes.oracle.util.SafeUrlValidator;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SSRF-guarded HTTP fetcher that downloads bounded HTML responses and delegates extraction
 * to {@link ArticleHtmlParser}.
 */
@Component
public class ArticleFetcher implements ContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([^;\\s\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private final FetcherProperties fetcherProps;
    private final HttpClient httpClient;
    private final SafeUrlValidator urlValidator;
    private final ArticleHtmlParser htmlParser;

    @Autowired
    public ArticleFetcher(
            OracleProperties props,
            HttpClient httpClient,
            SafeUrlValidator urlValidator,
            ArticleHtmlParser htmlParser
    ) {
        this(
                Objects.requireNonNull(props, "props must not be null").fetcher(),
                httpClient,
                urlValidator,
                htmlParser
        );
    }

    public ArticleFetcher(FetcherProperties fetcherProps, HttpClient httpClient) {
        this(fetcherProps, httpClient, new SafeUrlValidator(true));
    }

    public ArticleFetcher(
            FetcherProperties fetcherProps,
            HttpClient httpClient,
            SafeUrlValidator urlValidator
    ) {
        this(fetcherProps, httpClient, urlValidator, new ArticleHtmlParser(fetcherProps));
    }

    public ArticleFetcher(
            FetcherProperties fetcherProps,
            HttpClient httpClient,
            SafeUrlValidator urlValidator,
            ArticleHtmlParser htmlParser
    ) {
        this.fetcherProps = Objects.requireNonNull(fetcherProps, "fetcherProps must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.urlValidator = Objects.requireNonNull(urlValidator, "urlValidator must not be null");
        this.htmlParser = Objects.requireNonNull(htmlParser, "htmlParser must not be null");
    }

    /**
     * Fetches and parses the article HTML at {@code url}, falling back to {@link FetchStatus#TITLE_ONLY} on error.
     */
    @Override
    public FetchedArticle fetch(String url, String title) {
        if (url == null || url.isBlank()) {
            return FetchedArticle.titleOnly(title);
        }

        int maxRedirects = fetcherProps.maxRedirects();
        try {
            URI currentUri = urlValidator.validateAndParse(url);
            for (int hop = 0; hop <= maxRedirects; hop++) {
                urlValidator.validate(currentUri);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(currentUri)
                        .timeout(Duration.ofMillis(fetcherProps.timeoutMs()))
                        .header("User-Agent", fetcherProps.userAgent())
                        .header("Accept", "text/html,application/xhtml+xml")
                        .GET()
                        .build();

                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream bodyStream = response.body()) {
                    int status = response.statusCode();
                    if (isRedirectStatus(status)) {
                        if (hop >= maxRedirects) {
                            log.debug("Article fetch exceeded max redirects ({}) for {}", maxRedirects, url);
                            return FetchedArticle.titleOnly(title);
                        }
                        Optional<String> location = response.headers().firstValue("Location");
                        if (location.isEmpty() || location.get().isBlank()) {
                            return FetchedArticle.titleOnly(title);
                        }
                        currentUri = currentUri.resolve(location.get().trim());
                        continue;
                    }

                    if (status != 200) {
                        log.debug("Article fetch returned HTTP {} for {}; falling back to TITLE_ONLY", status, url);
                        return FetchedArticle.titleOnly(title);
                    }

                    String contentType = response.headers().firstValue("Content-Type").orElse("");
                    if (!contentType.toLowerCase(Locale.ROOT).contains("html")) {
                        log.debug("Article fetch returned non-HTML Content-Type '{}' for {}; falling back to TITLE_ONLY",
                                contentType, url);
                        return FetchedArticle.titleOnly(title);
                    }

                    byte[] bytes = BoundedStreamReader.readCappedBytes(
                            bodyStream, fetcherProps.maxBytes(), fetcherProps.streamBufferBytes());
                    Charset charset = extractCharset(contentType);
                    String html = new String(bytes, charset);
                    return htmlParser.parse(html, currentUri.toString(), title);
                }
            }
            return FetchedArticle.titleOnly(title);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Article fetch interrupted for {}; falling back to TITLE_ONLY", url);
            return FetchedArticle.titleOnly(title);
        } catch (Exception e) {
            log.debug("Article fetch failed for {} ({}); falling back to TITLE_ONLY", url, e.getMessage());
            return FetchedArticle.titleOnly(title);
        }
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Charset extractCharset(String contentType) {
        Matcher matcher = CHARSET_PATTERN.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1).trim());
            } catch (Exception ignored) {
                // Fall back to UTF-8 for unknown charsets.
            }
        }
        return StandardCharsets.UTF_8;
    }
}
