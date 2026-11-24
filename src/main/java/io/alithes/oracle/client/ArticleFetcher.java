package io.alithes.oracle.client;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.util.CryptoUtil;
import io.alithes.oracle.util.SafeUrlValidator;
import io.alithes.oracle.util.SimHash;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Downloads and extracts article body text and publication metadata using Jsoup.
 */
@Component
public class ArticleFetcher implements ContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);
    private static final int MIN_PARAGRAPH_CHARS = 40;
    private static final int MIN_EXTRACTED_CHARS = 300;
    private static final int MAX_EXCERPT_CHARS = 2000;
    private static final int MAX_REDIRECTS = 3;
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([^;\\s\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private final OracleProperties.Fetcher fetcherProps;
    private final HttpClient httpClient;
    private final SafeUrlValidator urlValidator;

    @Autowired
    public ArticleFetcher(OracleProperties props, HttpClient httpClient, SafeUrlValidator urlValidator) {
        this(Objects.requireNonNull(props, "props must not be null").fetcher(), httpClient, urlValidator);
    }

    public ArticleFetcher(OracleProperties.Fetcher fetcherProps, HttpClient httpClient) {
        this(fetcherProps, httpClient, new SafeUrlValidator(true));
    }

    public ArticleFetcher(
            OracleProperties.Fetcher fetcherProps,
            HttpClient httpClient,
            SafeUrlValidator urlValidator
    ) {
        this.fetcherProps = Objects.requireNonNull(fetcherProps, "fetcherProps must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.urlValidator = Objects.requireNonNull(urlValidator, "urlValidator must not be null");
    }

    /**
     * Fetches and parses the article HTML at {@code url}, falling back to {@link FetchStatus#TITLE_ONLY} on error.
     */
    @Override
    public FetchedArticle fetch(String url, String title) {
        if (url == null || url.isBlank()) {
            return FetchedArticle.titleOnly(title);
        }

        try {
            URI currentUri = urlValidator.validateAndParse(url);
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
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
                        if (hop >= MAX_REDIRECTS) {
                            log.debug("Article fetch exceeded max redirects ({}) for {}", MAX_REDIRECTS, url);
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

                    byte[] bytes = readCappedBytes(bodyStream, Math.max(0, fetcherProps.maxBytes()));
                    Charset charset = extractCharset(contentType);
                    String html = new String(bytes, charset);
                    return parseHtml(html, currentUri.toString(), title);
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

    private FetchedArticle parseHtml(String html, String url, String title) {
        Document doc = Jsoup.parse(html, url);

        // Extract publishedAt before removing boilerplate containers (e.g. <header>).
        Instant publishedAt = extractPublishedAt(doc);

        doc.select("script, style, nav, header, footer, aside, noscript, form, iframe, svg").remove();

        List<String> paragraphs = collectParagraphs(doc.select("article p, main p"));
        if (totalCharCount(paragraphs) < MIN_EXTRACTED_CHARS) {
            paragraphs = collectParagraphs(doc.select("body p"));
        }

        if (totalCharCount(paragraphs) < MIN_EXTRACTED_CHARS) {
            return FetchedArticle.titleOnly(title);
        }

        String joinedText = String.join("\n\n", paragraphs);
        String excerpt = joinedText.length() > MAX_EXCERPT_CHARS
                ? joinedText.substring(0, MAX_EXCERPT_CHARS)
                : joinedText;

        return new FetchedArticle(
                FetchStatus.OK,
                paragraphs,
                joinedText,
                publishedAt,
                CryptoUtil.sha256Hex(joinedText),
                excerpt,
                SimHash.compute(joinedText)
        );
    }

    private static List<String> collectParagraphs(Elements elements) {
        List<String> result = new ArrayList<>();
        for (Element el : elements) {
            String text = el.text().trim();
            if (text.length() >= MIN_PARAGRAPH_CHARS) {
                result.add(text);
            }
        }
        return result;
    }

    private static int totalCharCount(List<String> paragraphs) {
        int sum = 0;
        for (String p : paragraphs) {
            sum += p.length();
        }
        return sum;
    }

    private static Instant extractPublishedAt(Document doc) {
        List<String> candidates = new ArrayList<>();
        for (Element el : doc.select("meta[property=article:published_time]")) {
            candidates.add(el.attr("content"));
        }
        for (Element el : doc.select("meta[name=pubdate]")) {
            candidates.add(el.attr("content"));
        }
        for (Element el : doc.select("meta[name=publish-date]")) {
            candidates.add(el.attr("content"));
        }
        for (Element el : doc.select("time[datetime]")) {
            candidates.add(el.attr("datetime"));
        }

        for (String candidate : candidates) {
            Instant parsed = tryParseInstant(candidate);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Instant tryParseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private static byte[] readCappedBytes(InputStream inputStream, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int remaining = maxBytes;
        while (remaining > 0) {
            int toRead = Math.min(buffer.length, remaining);
            int read = inputStream.read(buffer, 0, toRead);
            if (read == -1) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
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
