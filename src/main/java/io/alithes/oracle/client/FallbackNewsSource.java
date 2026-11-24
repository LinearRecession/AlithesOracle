package io.alithes.oracle.client;

import io.alithes.oracle.config.OracleProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Primary {@link NewsSource} that queries GDELT first and falls back to Bing News RSS
 * (direct publisher URLs) and then Google News RSS when GDELT throttles, times out, or rejects a query.
 */
@Component
@Primary
public class FallbackNewsSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(FallbackNewsSource.class);

    private final NewsSource primary;
    private final NewsSource bingFallback;
    private final NewsSource googleFallback;
    private final OracleProperties.News newsProps;
    private final Clock clock;
    private volatile Instant gdeltCooldownUntil = Instant.EPOCH;

    @Autowired
    public FallbackNewsSource(
            GdeltClient primary,
            BingNewsRssClient bingFallback,
            GoogleNewsRssClient googleFallback,
            OracleProperties props,
            Clock clock
    ) {
        this(
                primary,
                bingFallback,
                googleFallback,
                Objects.requireNonNull(props, "props must not be null").news(),
                clock
        );
    }

    public FallbackNewsSource(
            NewsSource primary,
            NewsSource bingFallback,
            NewsSource googleFallback,
            OracleProperties.News newsProps,
            Clock clock
    ) {
        this.primary = Objects.requireNonNull(primary, "primary must not be null");
        this.bingFallback = Objects.requireNonNull(bingFallback, "bingFallback must not be null");
        this.googleFallback = Objects.requireNonNull(googleFallback, "googleFallback must not be null");
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public List<GdeltArticle> search(String query, Instant from, Instant to) {
        Instant now = clock.instant();
        if (hasAnyFallback() && now.isBefore(gdeltCooldownUntil)) {
            log.info("Skipping GDELT during cooldown until {}; using RSS fallback for query '{}'",
                    gdeltCooldownUntil, query);
            return searchFallbacks(query, from, to);
        }

        try {
            return primary.search(query, from, to);
        } catch (GdeltQueryException e) {
            if (!hasAnyFallback()) {
                throw e;
            }
            log.warn("GDELT rejected query '{}' ({}); falling back to RSS", query, e.getMessage());
            return searchFallbacks(query, from, to);
        } catch (RuntimeException e) {
            if (!hasAnyFallback()) {
                throw e;
            }
            long cooldownMs = Math.max(0L, newsProps.gdeltCooldownMs());
            gdeltCooldownUntil = clock.instant().plusMillis(cooldownMs);
            log.warn("GDELT failed for query '{}' ({}); falling back to RSS (cooldown {}ms)",
                    query, e.getMessage(), cooldownMs);
            return searchFallbacks(query, from, to);
        }
    }

    private boolean hasAnyFallback() {
        return newsProps.bingRssEnabled() || newsProps.googleRssEnabled();
    }

    private List<GdeltArticle> searchFallbacks(String query, Instant from, Instant to) {
        if (newsProps.bingRssEnabled()) {
            try {
                List<GdeltArticle> bingArticles = bingFallback.search(query, from, to);
                if (!bingArticles.isEmpty() || !newsProps.googleRssEnabled()) {
                    return bingArticles;
                }
                log.info("Bing News RSS returned 0 articles for '{}'; trying Google News RSS", query);
            } catch (RuntimeException e) {
                if (!newsProps.googleRssEnabled()) {
                    throw e;
                }
                log.warn("Bing News RSS failed for '{}' ({}); falling back to Google News RSS",
                        query, e.getMessage());
            }
        }
        return googleFallback.search(query, from, to);
    }
}
