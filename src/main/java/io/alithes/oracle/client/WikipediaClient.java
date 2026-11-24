package io.alithes.oracle.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.service.QueryCompiler;
import io.alithes.oracle.util.UrlNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Targeted MediaWiki search client that surfaces at most {@code wikipediaMaxPages} relevant Wikipedia
 * articles for a natural-language question without crawling, and harvests up to
 * {@code wikipediaMaxCitations} external news URLs from footnotes on question-matching paragraphs.
 */
@Component
public class WikipediaClient {

    private static final int MAX_HTML_BYTES = 2_097_152;
    private static final int MAX_REDIRECTS = 2;
    private static final int MAX_CITATION_TITLE_CHARS = 160;
    private static final Set<String> SKIPPED_DOMAINS = Set.of(
            "wikipedia.org", "wikimedia.org", "wikidata.org",
            "twitter.com", "x.com", "facebook.com", "instagram.com",
            "youtube.com", "tiktok.com", "linkedin.com",
            "doi.org", "worldcat.org", "isbn",
            "web.archive.org", "archive.is", "archive.ph", "archive.today"
    );

    private final OracleProperties.News newsProps;
    private final String userAgent;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private final String baseUrl;
    private final String domain;

    @Autowired
    public WikipediaClient(
            OracleProperties props,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock
    ) {
        this(
                Objects.requireNonNull(props, "props must not be null").news(),
                props.fetcher().userAgent(),
                objectMapper,
                httpClient,
                clock
        );
    }

    public WikipediaClient(
            OracleProperties.News newsProps,
            String userAgent,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock
    ) {
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? "AlithesOracle/1.0 (+https://alithes.io)"
                : userAgent;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        String rawBaseUrl = Objects.requireNonNull(newsProps.wikipediaBaseUrl(), "wikipediaBaseUrl must not be null");
        this.baseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1) : rawBaseUrl;
        this.domain = extractHost(this.baseUrl);
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
        List<String> entities = QueryCompiler.extractEntities(questionText);
        List<String> distinctiveTerms = QueryCompiler.extractDistinctiveTerms(questionText);

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
     * Fetches the discovered Wikipedia pages and harvests up to {@code wikipediaMaxCitations}
     * distinct-domain external news citations attached to question-matching paragraphs/rows.
     */
    public List<GdeltArticle> harvestCitations(String questionText, List<GdeltArticle> wikiPages) {
        int maxCitations = newsProps.wikipediaMaxCitations();
        if (!newsProps.wikipediaEnabled()
                || maxCitations <= 0
                || questionText == null
                || questionText.isBlank()
                || wikiPages == null
                || wikiPages.isEmpty()) {
            return List.of();
        }

        Set<String> terms = extractQueryTerms(questionText);
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
                String noteId = href.substring(1);
                Element note = doc.getElementById(noteId);
                if (note == null) {
                    continue;
                }
                harvestNoteLinks(note, maxCitations, byCanonicalDomain);
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

    private static String resolveCitationTitle(Element link, Element note) {
        String anchor = link.text().trim();
        if (anchor.length() >= 10) {
            return anchor.length() > MAX_CITATION_TITLE_CHARS
                    ? anchor.substring(0, MAX_CITATION_TITLE_CHARS)
                    : anchor;
        }
        String noteText = note.text().trim();
        return noteText.length() > MAX_CITATION_TITLE_CHARS
                ? noteText.substring(0, MAX_CITATION_TITLE_CHARS)
                : noteText;
    }

    private static boolean isSkippedDomain(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        for (String skipped : SKIPPED_DOMAINS) {
            if (lower.equals(skipped) || lower.endsWith("." + skipped)) {
                return true;
            }
        }
        return false;
    }

    private String fetchHtmlQuietly(String url) {
        try {
            URI current = URI.create(url.trim());
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
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
                        if (loc.isEmpty() || loc.get().isBlank() || hop >= MAX_REDIRECTS) {
                            return null;
                        }
                        current = current.resolve(loc.get().trim());
                        continue;
                    }
                    if (status != 200) {
                        return null;
                    }
                    return new String(readCappedBytes(body, MAX_HTML_BYTES), StandardCharsets.UTF_8);
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

    private static byte[] readCappedBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
        byte[] buf = new byte[8192];
        int remaining = maxBytes;
        while (remaining > 0) {
            int read = in.read(buf, 0, Math.min(buf.length, remaining));
            if (read == -1) {
                break;
            }
            out.write(buf, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    private static Set<String> extractQueryTerms(String questionText) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : QueryCompiler.extractDistinctiveTerms(questionText)) {
            for (String token : term.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.length() >= 4) {
                    terms.add(token);
                }
            }
        }
        return terms;
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
                String wikiUrl = buildArticleUrl(title);
                Instant seenDate = parseTimestamp(hit.timestamp());
                articles.add(new GdeltArticle(
                        wikiUrl,
                        title,
                        seenDate,
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
        String host = extractHostOrEmpty(url);
        return host.isEmpty() ? "en.wikipedia.org" : host;
    }

    private static String extractHostOrEmpty(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "";
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
