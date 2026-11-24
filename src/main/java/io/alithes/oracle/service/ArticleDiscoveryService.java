package io.alithes.oracle.service;

import io.alithes.oracle.client.GdeltArticle;
import io.alithes.oracle.client.NewsSource;
import io.alithes.oracle.client.SupplementalEvidenceSource;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.util.UrlNormalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Discovers candidate articles across the primary {@link NewsSource} chain and configured
 * {@link SupplementalEvidenceSource} strategies, deduplicating by canonical URL.
 */
@Service
public class ArticleDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ArticleDiscoveryService.class);

    public record DiscoveryResult(
            Map<String, GdeltArticle> uniqueArticles,
            int totalFound,
            boolean primarySourceOk,
            List<String> errors
    ) {
        public DiscoveryResult {
            uniqueArticles = uniqueArticles == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(uniqueArticles));
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    private final NewsSource newsSource;
    private final List<SupplementalEvidenceSource> supplementalSources;

    public ArticleDiscoveryService(NewsSource newsSource, List<SupplementalEvidenceSource> supplementalSources) {
        this.newsSource = Objects.requireNonNull(newsSource, "newsSource must not be null");
        this.supplementalSources = supplementalSources == null ? List.of() : List.copyOf(supplementalSources);
    }

    public DiscoveryResult discover(Question question, Instant scanFrom, Instant scanTo) {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(scanFrom, "scanFrom must not be null");
        Objects.requireNonNull(scanTo, "scanTo must not be null");

        if (!scanFrom.isBefore(scanTo)) {
            return new DiscoveryResult(Map.of(), 0, true, List.of());
        }

        boolean primaryOk = true;
        int totalFound = 0;
        List<String> errors = new ArrayList<>();
        Map<String, GdeltArticle> uniqueArticles = new LinkedHashMap<>();

        for (String query : question.searchQueries()) {
            try {
                List<GdeltArticle> articles = newsSource.search(query, scanFrom, scanTo);
                totalFound += addArticles(articles, uniqueArticles);
            } catch (Exception e) {
                primaryOk = false;
                errors.add(query + ": " + e.getMessage());
                log.warn("News search failed for question {} query '{}': {}",
                        question.id(), query, e.getMessage());
            }
        }

        for (SupplementalEvidenceSource supplemental : supplementalSources) {
            try {
                List<GdeltArticle> articles = supplemental.collect(question.text());
                totalFound += addArticles(articles, uniqueArticles);
            } catch (Exception e) {
                log.warn("Supplemental source {} failed for question {}: {}",
                        supplemental.getClass().getSimpleName(), question.id(), e.getMessage());
            }
        }

        return new DiscoveryResult(uniqueArticles, totalFound, primaryOk, errors);
    }

    private static int addArticles(List<GdeltArticle> articles, Map<String, GdeltArticle> uniqueArticles) {
        if (articles == null || articles.isEmpty()) {
            return 0;
        }
        for (GdeltArticle article : articles) {
            if (article == null || article.url() == null) {
                continue;
            }
            String normalizedUrl = UrlNormalizer.normalize(article.url());
            if (!normalizedUrl.isBlank()) {
                uniqueArticles.putIfAbsent(normalizedUrl, article);
            }
        }
        return articles.size();
    }
}
