package io.alithes.oracle.client;

import io.alithes.oracle.config.FetcherProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.util.CryptoUtil;
import io.alithes.oracle.util.SimHash;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pure HTML parser extracting publication timestamps, clean article paragraphs, excerpts,
 * SHA-256 content hashes, and SimHash fingerprints.
 */
@Component
public class ArticleHtmlParser {

    private final FetcherProperties fetcherProps;

    @Autowired
    public ArticleHtmlParser(OracleProperties props) {
        this(Objects.requireNonNull(props, "props must not be null").fetcher());
    }

    public ArticleHtmlParser(FetcherProperties fetcherProps) {
        this.fetcherProps = Objects.requireNonNull(fetcherProps, "fetcherProps must not be null");
    }

    /**
     * Parses article HTML into a {@link FetchedArticle}, falling back to {@link FetchStatus#TITLE_ONLY}
     * when extracted text falls below {@code minExtractedChars}.
     */
    public FetchedArticle parse(String html, String url, String title) {
        Document doc = Jsoup.parse(html, url);

        // Extract publishedAt before removing boilerplate containers (e.g. <header>).
        Instant publishedAt = extractPublishedAt(doc);

        doc.select("script, style, nav, header, footer, aside, noscript, form, iframe, svg").remove();

        int minParagraphChars = fetcherProps.minParagraphChars();
        int minExtractedChars = fetcherProps.minExtractedChars();
        int maxExcerptChars = fetcherProps.maxExcerptChars();

        List<String> paragraphs = collectParagraphs(doc.select("article p, main p"), minParagraphChars);
        if (totalCharCount(paragraphs) < minExtractedChars) {
            paragraphs = collectParagraphs(doc.select("body p"), minParagraphChars);
        }

        if (totalCharCount(paragraphs) < minExtractedChars) {
            return FetchedArticle.titleOnly(title);
        }

        String joinedText = String.join("\n\n", paragraphs);
        String excerpt = joinedText.length() > maxExcerptChars
                ? joinedText.substring(0, maxExcerptChars)
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

    private static List<String> collectParagraphs(Elements elements, int minParagraphChars) {
        List<String> result = new ArrayList<>();
        for (Element el : elements) {
            String text = el.text().trim();
            if (text.length() >= minParagraphChars) {
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
}
