package io.alithes.oracle.client;

import io.alithes.oracle.config.FetcherProperties;
import io.alithes.oracle.config.NewsProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.util.BoundedStreamReader;
import io.alithes.oracle.util.QuestionTextAnalyzer;
import io.alithes.oracle.util.UrlNormalizer;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Extracts and domain-deduplicates external news citations from footnotes on Wikipedia
 * paragraphs matching the question's distinctive terms.
 */
@Component
public class WikipediaCitationHarvester {

    private final NewsProperties newsProps;
    private final int maxHtmlBytes;
    private final int streamBufferBytes;
    private final String userAgent;
    private final HttpClient httpClient;
    private final Clock clock;

    @Autowired
    public WikipediaCitationHarvester(OracleProperties props, HttpClient httpClient, Clock clock) {
        this(
                Objects.requireNonNull(props, "props must not be null").news(),
                props.fetcher().maxBytes(),
                props.fetcher().streamBufferBytes(),
                props.fetcher().userAgent(),
                httpClient,
                clock
        );
    }

    public WikipediaCitationHarvester(
            NewsProperties newsProps,
            int maxHtmlBytes,
            int streamBufferBytes,
            String userAgent,
            HttpClient httpClient,
            Clock clock
    ) {
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        this.maxHtmlBytes = maxHtmlBytes > 0 ? maxHtmlBytes : FetcherProperties.defaults().maxBytes();
        this.streamBufferBytes = streamBufferBytes > 0
                ? streamBufferBytes
                : FetcherProperties.defaults().streamBufferBytes();
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? FetcherProperties.defaults().userAgent()
                : userAgent;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Fetches {@code wikiPages} and harvests up to {@code wikipediaMaxCitations} distinct-domain
     * external citations linked from paragraphs relevant to {@code questionText}.
     */
    public List<GdeltArticle> harvest(String questionText, List<GdeltArticle> wikiPages) {
        int maxCitations = newsProps.wikipediaMaxCitations();
        if (!newsProps.wikipediaEnabled()
                || maxCitations <= 0
                || questionText == null
                || questionText.isBlank()
                || wikiPages == null
                || wikiPages.isEmpty()) {
            return List.of();
        }

        Set<String> terms = QuestionTextAnalyzer.extractSearchTokens(questionText, 4);
        if (terms.isEmpty()) {
            return List.of();
        }
        int minHits = terms.size() >= 2 ? 2 : 1;

        Map<String, GdeltArticle> byCanonicalDomain = new LinkedHashMap<>();
        for (GdeltArticle wikiPage : wikiPages) {
            if (byCanonicalDomain.size() >= maxCitations) {
                break;
            }
            if (wikiPage == null || wikiPage.url() == null || wikiPage.url().isBlank()) {
                continue;
            }
            String html = fetchHtmlQuietly(wikiPage.url());
            if (html == null || html.isBlank()) {
                continue;
            }
            extractCitationsFromPage(html, wikiPage.url(), terms, minHits, maxCitations, byCanonicalDomain);
        }

        return List.copyOf(byCanonicalDomain.values());
    }

    private void extractCitationsFromPage(
            String html,
            String pageUrl,
            Set<String> terms,
            int minHits,
            int maxCitations,
            Map<String, GdeltArticle> byCanonicalDomain
    ) {
        Document doc = Jsoup.parse(html, pageUrl);
        Elements blocks = doc.select("#mw-content-text p, #mw-content-text tr, #mw-content-text li");
        if (blocks.isEmpty()) {
            blocks = doc.select("body p, body tr, body li");
        }

        record ScoredBlock(Element element, int hits, int order) {
        }
        List<ScoredBlock> matching = new ArrayList<>();
        int order = 0;
        for (Element block : blocks) {
            if (block.closest(".reflist, ol.references") != null) {
                continue;
            }
            if (block.select("sup.reference a[href^=#cite_note-]").isEmpty()) {
                continue;
            }
            String lower = block.text().toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String term : terms) {
                if (lower.contains(term)) {
                    hits++;
                }
            }
            if (hits >= minHits) {
                matching.add(new ScoredBlock(block, hits, order++));
            }
        }

        matching.sort(Comparator.comparingInt(ScoredBlock::hits).reversed()
                .thenComparingInt(ScoredBlock::order));

        for (ScoredBlock sb : matching) {
            if (byCanonicalDomain.size() >= maxCitations) {
                break;
            }
            for (Element refLink : sb.element().select("sup.reference a[href^=#cite_note-]")) {
                if (byCanonicalDomain.size() >= maxCitations) {
                    break;
                }
                String href = refLink.attr("href");
                if (href.length() <= 1) {
                    continue;
                }
                Element note = doc.getElementById(href.substring(1));
                if (note != null) {
                    harvestNoteLinks(note, maxCitations, byCanonicalDomain);
                }
            }
        }
    }

    private void harvestNoteLinks(
            Element note,
            int maxCitations,
            Map<String, GdeltArticle> byCanonicalDomain
    ) {
        Elements links = note.select("a.external[href]");
        if (links.isEmpty()) {
            links = note.select("a[href^=http]");
        }
        for (Element link : links) {
            if (byCanonicalDomain.size() >= maxCitations) {
                break;
            }
            String absUrl = link.absUrl("href");
            if (absUrl.isBlank()) {
                absUrl = link.attr("href").trim();
            }
            if (!absUrl.startsWith("http://") && !absUrl.startsWith("https://")) {
                continue;
            }
            String host = extractHostOrEmpty(absUrl);
            if (host.isBlank() || isSkippedDomain(host)) {
                continue;
            }
            String canonical = UrlNormalizer.stripHostPrefixes(host);
            if (canonical.isBlank() || byCanonicalDomain.containsKey(canonical)) {
                continue;
            }
            String title = resolveCitationTitle(link, note);
            byCanonicalDomain.put(canonical, new GdeltArticle(
                    absUrl,
                    title,
                    clock.instant(),
                    host,
                    "Unknown",
                    "International"
            ));
        }
    }

    private String resolveCitationTitle(Element link, Element note) {
        int maxTitleChars = newsProps.wikipediaMaxCitationTitleChars();
        String anchor = link.text().trim();
        if (anchor.length() >= 10) {
            return anchor.length() > maxTitleChars ? anchor.substring(0, maxTitleChars) : anchor;
        }
        String noteText = note.text().trim();
        return noteText.length() > maxTitleChars ? noteText.substring(0, maxTitleChars) : noteText;
    }

    private boolean isSkippedDomain(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        for (String skipped : newsProps.wikipediaSkippedDomains()) {
            if (lower.equals(skipped) || lower.endsWith("." + skipped)) {
                return true;
            }
        }
        return false;
    }

    private String fetchHtmlQuietly(String url) {
        int maxRedirects = newsProps.wikipediaMaxRedirects();
        try {
            URI current = URI.create(url.trim());
            for (int hop = 0; hop <= maxRedirects; hop++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(current)
                        .timeout(Duration.ofMillis(newsProps.wikipediaTimeoutMs()))
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        Optional<String> loc = response.headers().firstValue("Location");
                        if (loc.isEmpty() || loc.get().isBlank() || hop >= maxRedirects) {
                            return null;
                        }
                        current = current.resolve(loc.get().trim());
                        continue;
                    }
                    if (status != 200) {
                        return null;
                    }
                    return new String(
                            BoundedStreamReader.readCappedBytes(body, maxHtmlBytes, streamBufferBytes),
                            StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractHostOrEmpty(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
