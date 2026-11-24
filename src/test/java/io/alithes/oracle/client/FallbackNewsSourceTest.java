package io.alithes.oracle.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.alithes.oracle.config.NewsProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FallbackNewsSourceTest {

    private static final Instant START = Instant.parse("2026-11-24T10:00:00Z");

    @Test
    void returnsPrimaryResultWithoutCallingFallbacksWhenPrimarySucceeds() {
        MutableClock clock = new MutableClock(START);
        AtomicInteger bingCalls = new AtomicInteger();
        AtomicInteger googleCalls = new AtomicInteger();
        GdeltArticle expected = new GdeltArticle(
                "https://reuters.com/1", "Title", START, "reuters.com", "English", "US");

        FallbackNewsSource source = new FallbackNewsSource(
                (q, f, t) -> List.of(expected),
                (q, f, t) -> {
                    bingCalls.incrementAndGet();
                    return List.of();
                },
                (q, f, t) -> {
                    googleCalls.incrementAndGet();
                    return List.of();
                },
                NewsProperties.defaults(),
                clock
        );

        List<GdeltArticle> result = source.search("nvidia", START.minusSeconds(3600), START);

        assertThat(result).containsExactly(expected);
        assertThat(bingCalls.get()).isZero();
        assertThat(googleCalls.get()).isZero();
    }

    @Test
    void fallsBackToBingFirstEngagesCooldownAndRecoversAfterCooldown() {
        MutableClock clock = new MutableClock(START);
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger bingCalls = new AtomicInteger();
        AtomicInteger googleCalls = new AtomicInteger();

        GdeltArticle bingArticle = new GdeltArticle(
                "https://apnews.com/1", "Bing Title", START, "apnews.com", "English", "US");
        GdeltArticle recoveredArticle = new GdeltArticle(
                "https://reuters.com/2", "Primary Recovered", START, "reuters.com", "English", "US");

        var newsProps = new NewsProperties(
                true, "https://www.bing.com", 5000L, 20,
                true, "https://news.google.com", 5000L, 20,
                60000L,
                true, "https://en.wikipedia.org", 6000L, 3, 5
        );

        FallbackNewsSource source = new FallbackNewsSource(
                (q, f, t) -> {
                    int call = primaryCalls.incrementAndGet();
                    if (call == 1) {
                        throw new GdeltException("HTTP 429", 429, "rate limited");
                    }
                    return List.of(recoveredArticle);
                },
                (q, f, t) -> {
                    bingCalls.incrementAndGet();
                    return List.of(bingArticle);
                },
                (q, f, t) -> {
                    googleCalls.incrementAndGet();
                    return List.of();
                },
                newsProps,
                clock
        );

        // First call fails on GDELT -> triggers Bing fallback + 60s cooldown
        assertThat(source.search("q1", START.minusSeconds(3600), START)).containsExactly(bingArticle);
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(bingCalls.get()).isEqualTo(1);
        assertThat(googleCalls.get()).isZero();

        // Second call within cooldown -> skips primary, uses Bing
        clock.advance(Duration.ofSeconds(30));
        assertThat(source.search("q2", START.minusSeconds(3600), START)).containsExactly(bingArticle);
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(bingCalls.get()).isEqualTo(2);
        assertThat(googleCalls.get()).isZero();

        // Call after cooldown expires -> probes primary again
        clock.advance(Duration.ofSeconds(31));
        assertThat(source.search("q3", START.minusSeconds(3600), START)).containsExactly(recoveredArticle);
        assertThat(primaryCalls.get()).isEqualTo(2);
        assertThat(bingCalls.get()).isEqualTo(2);
    }

    @Test
    void fallsThroughFromBingToGoogleWhenBingFailsOrReturnsEmpty() {
        MutableClock clock = new MutableClock(START);
        AtomicInteger bingCalls = new AtomicInteger();
        GdeltArticle googleArticle = new GdeltArticle(
                "https://news.google.com/rss/articles/1", "Google Title", START, "bbc.com", "English", "US");

        FallbackNewsSource source = new FallbackNewsSource(
                (q, f, t) -> {
                    throw new GdeltException("timeout", -1, null);
                },
                (q, f, t) -> {
                    if (bingCalls.incrementAndGet() == 1) {
                        throw new GdeltException("Bing 503", 503, "");
                    }
                    return List.of();
                },
                (q, f, t) -> List.of(googleArticle),
                NewsProperties.defaults(),
                clock
        );

        assertThat(source.search("q1", START.minusSeconds(3600), START)).containsExactly(googleArticle);
        assertThat(source.search("q2", START.minusSeconds(3600), START)).containsExactly(googleArticle);
        assertThat(bingCalls.get()).isEqualTo(2);
    }

    @Test
    void propagatesExceptionWhenBothFallbacksAreDisabled() {
        MutableClock clock = new MutableClock(START);
        var disabledProps = new NewsProperties(
                false, "https://www.bing.com", 5000L, 20,
                false, "https://news.google.com", 5000L, 20,
                60000L,
                false, "https://en.wikipedia.org", 6000L, 3, 0
        );

        FallbackNewsSource source = new FallbackNewsSource(
                (q, f, t) -> {
                    throw new GdeltException("HTTP 429", 429, "rate limited");
                },
                (q, f, t) -> List.of(),
                (q, f, t) -> List.of(),
                disabledProps,
                clock
        );

        assertThatThrownBy(() -> source.search("q1", START.minusSeconds(3600), START))
                .isInstanceOf(GdeltException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
