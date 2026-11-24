package io.alithes.oracle.client;

import io.alithes.oracle.config.NewsProperties;
import io.alithes.oracle.config.OracleProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Primary {@link NewsSource} querying GDELT first and failing over through an ordered chain
 * of RSS fallback providers under a bounded cooldown guard.
 */
@Component
@Primary
public class FallbackNewsSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(FallbackNewsSource.class);

    private final NewsSource primary;
    private final List<FallbackProvider> fallbackChain;
    private final NewsProperties newsProps;
    private final CooldownGuard cooldownGuard;

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
            NewsProperties newsProps,
            Clock clock
    ) {
        this.primary = Objects.requireNonNull(primary, "primary must not be null");
        this.newsProps = Objects.requireNonNull(newsProps, "newsProps must not be null");
        this.cooldownGuard = new CooldownGuard(Objects.requireNonNull(clock, "clock must not be null"));
        this.fallbackChain = List.of(
                new FallbackProvider("Bing News RSS", newsProps::bingRssEnabled,
                        Objects.requireNonNull(bingFallback, "bingFallback must not be null")),
                new FallbackProvider("Google News RSS", newsProps::googleRssEnabled,
                        Objects.requireNonNull(googleFallback, "googleFallback must not be null"))
        );
    }

    @Override
    public List<GdeltArticle> search(String query, Instant from, Instant to) {
        List<FallbackProvider> activeFallbacks = enabledFallbacks();
        if (!activeFallbacks.isEmpty() && cooldownGuard.isActive()) {
            log.info("Skipping GDELT during cooldown until {}; using RSS fallback for query '{}'",
                    cooldownGuard.activeUntil(), query);
            return executeFallbackChain(activeFallbacks, query, from, to);
        }

        try {
            return primary.search(query, from, to);
        } catch (GdeltQueryException e) {
            if (activeFallbacks.isEmpty()) {
                throw e;
            }
            log.warn("GDELT rejected query '{}' ({}); falling back to RSS", query, e.getMessage());
            return executeFallbackChain(activeFallbacks, query, from, to);
        } catch (RuntimeException e) {
            if (activeFallbacks.isEmpty()) {
                throw e;
            }
            long cooldownMs = Math.max(0L, newsProps.gdeltCooldownMs());
            cooldownGuard.activate(Duration.ofMillis(cooldownMs));
            log.warn("GDELT failed for query '{}' ({}); falling back to RSS (cooldown {}ms)",
                    query, e.getMessage(), cooldownMs);
            return executeFallbackChain(activeFallbacks, query, from, to);
        }
    }

    private List<FallbackProvider> enabledFallbacks() {
        return fallbackChain.stream()
                .filter(p -> p.enabled().getAsBoolean())
                .toList();
    }

    private List<GdeltArticle> executeFallbackChain(
            List<FallbackProvider> providers,
            String query,
            Instant from,
            Instant to
    ) {
        for (int i = 0; i < providers.size(); i++) {
            FallbackProvider provider = providers.get(i);
            boolean hasNext = i < providers.size() - 1;
            try {
                List<GdeltArticle> articles = provider.source().search(query, from, to);
                if (!articles.isEmpty() || !hasNext) {
                    return articles;
                }
                log.info("{} returned 0 articles for '{}'; trying {}",
                        provider.name(), query, providers.get(i + 1).name());
            } catch (RuntimeException e) {
                if (!hasNext) {
                    throw e;
                }
                log.warn("{} failed for '{}' ({}); falling back to {}",
                        provider.name(), query, e.getMessage(), providers.get(i + 1).name());
            }
        }
        return List.of();
    }
}
