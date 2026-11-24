package io.alithes.oracle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration bound from {@code oracle.*} properties.
 */
@ConfigurationProperties(prefix = "oracle")
public record OracleProperties(
        Jev jev,
        Gdelt gdelt,
        Fetcher fetcher,
        Scanner scanner,
        Webhook webhook,
        Policy policy,
Security security,
        News news) {

    public OracleProperties {
        if (jev == null) {
            jev = Jev.defaults();
        }
        if (gdelt == null) {
            gdelt = Gdelt.defaults();
        }
        if (fetcher == null) {
            fetcher = Fetcher.defaults();
        }
        if (scanner == null) {
            scanner = Scanner.defaults();
        }
        if (webhook == null) {
            webhook = Webhook.defaults();
        }
        if (policy == null) {
            policy = Policy.defaults();
        }
        if (security == null) {
            security = Security.defaults();
        }
        if (news == null) {
            news = News.defaults();
        }
    }

    public static OracleProperties defaults() {
        return new OracleProperties(
                Jev.defaults(),
                Gdelt.defaults(),
                Fetcher.defaults(),
                Scanner.defaults(),
                Webhook.defaults(),
                Policy.defaults(),
                Security.defaults(),
                News.defaults()
        );
    }

    public record Jev(
            String baseUrl,
            String apiKey,
            String model,
            boolean rejectAlias,
            long timeoutMs,
            int maxRetries,
            long retryBaseDelayMs) {
        public Jev {
            requirePositive(timeoutMs, "oracle.jev.timeout-ms");
            requireNonNegative(maxRetries, "oracle.jev.max-retries");
            requireNonNegative(retryBaseDelayMs, "oracle.jev.retry-base-delay-ms");
        }

        public static Jev defaults() {
            return new Jev(
                    "https://api.typesafe.ai",
                    "",
                    "jev-1.14.0",
                    true,
                    15000L,
                    3,
                    1000L);
        }}

        public record Gdelt(
                String baseUrl,
                long minIntervalMs,
                long timeoutMs,
                int maxRetries,
                long retryBaseDelayMs,
                int maxWindowSplitDepth) {
        public Gdelt {
            requireNonNegative(minIntervalMs, "oracle.gdelt.min-interval-ms");
            requirePositive(timeoutMs, "oracle.gdelt.timeout-ms");
            requireNonNegative(maxRetries, "oracle.gdelt.max-retries");
            requireNonNegative(retryBaseDelayMs, "oracle.gdelt.retry-base-delay-ms");
            requireNonNegative(maxWindowSplitDepth, "oracle.gdelt.max-window-split-depth");
        }

            public static Gdelt defaults() {
                return new Gdelt(
                        "https://api.gdeltproject.org",
                        5500L,
                        15000L,
                        3,
                        6000L,
                        3);
            }}

            public record Fetcher(
                    long timeoutMs,
                    int maxBytes,
                    String userAgent) {
        public Fetcher {
            requirePositive(timeoutMs, "oracle.fetcher.timeout-ms");
            requirePositive(maxBytes, "oracle.fetcher.max-bytes");
        }

                public static Fetcher defaults() {
                    return new Fetcher(
                            10000L,
                            2097152,
                            "AlithesOracle/1.0 (+https://alithes.io)");
                }}

                public record Scanner(
                        boolean enabled,
                        long fixedDelayMs,
                        int overlapMinutes,
                        int maxArticlesPerScan,
                double headlineMinScore,
                int maxConcurrency) {

        public Scanner {
            requirePositive(fixedDelayMs, "oracle.scanner.fixed-delay-ms");
            requireNonNegative(overlapMinutes, "oracle.scanner.overlap-minutes");
            requireNonNegative(maxArticlesPerScan, "oracle.scanner.max-articles-per-scan");
            requireProbability(headlineMinScore, "oracle.scanner.headline-min-score");
            requirePositive(maxConcurrency, "oracle.scanner.max-concurrency");
        }

        public static Scanner defaults() {
            return new Scanner(
                    false,
                    900000L,
                    30,
                    30,
                    0.30,
                    8
            );
        }}

                    public record Webhook(
                            String secret,
                            long timeoutMs,
                            int maxRetries) {
        public Webhook {
            requirePositive(timeoutMs, "oracle.webhook.timeout-ms");
            requireNonNegative(maxRetries, "oracle.webhook.max-retries");
        }

                        public static Webhook defaults() {
                            return new Webhook(
                                    "dev-secret-change-me",
                                    5000L,
                                    3);
                        }}

    public record Policy(
            int version,
            double minClusterConfirmWeight,
            int minConfirmClusters,
            int minTrustedClusters,
            double minConfirmSum,
            double maxDenyRatio,
            double disputeThreshold,
            double postDeadlineDisputeConfirmSum,
            double maxFailedScanRatio,
            boolean jevSynthesisEnabled
    ) {
        public Policy {
            requirePositive(version, "oracle.policy.version");
            requireProbability(minClusterConfirmWeight, "oracle.policy.min-cluster-confirm-weight");
            requirePositive(minConfirmClusters, "oracle.policy.min-confirm-clusters");
            requireNonNegative(minTrustedClusters, "oracle.policy.min-trusted-clusters");
            requirePositiveDouble(minConfirmSum, "oracle.policy.min-confirm-sum");
            requireProbability(maxDenyRatio, "oracle.policy.max-deny-ratio");
            requirePositiveDouble(disputeThreshold, "oracle.policy.dispute-threshold");
            requireNonNegativeDouble(postDeadlineDisputeConfirmSum, "oracle.policy.post-deadline-dispute-confirm-sum");
            requireProbability(maxFailedScanRatio, "oracle.policy.max-failed-scan-ratio");
        }

        public static Policy defaults() {
            return new Policy(
                    1,
                    0.55,
                    3,
                    2,
                    2.20,
                    0.50,
                    1.50,
                    1.00,
                    0.20,
                    false
            );
        }
    }

                            public record Security(boolean allowPrivateNetworkUrls) {
                                public static Security defaults() {
                                    return new Security(false);
                                }
                            }

    public record News(
            boolean bingRssEnabled,
            String bingRssBaseUrl,
            long bingRssTimeoutMs,
            int bingRssMaxResults,
            boolean googleRssEnabled,
            String googleRssBaseUrl,
            long googleRssTimeoutMs,
            int googleRssMaxResults,
            long gdeltCooldownMs,
            boolean wikipediaEnabled,
            String wikipediaBaseUrl,
            long wikipediaTimeoutMs,
            int wikipediaMaxPages,
            int wikipediaMaxCitations
    ) {
        public News {
            requirePositive(bingRssTimeoutMs, "oracle.news.bing-rss-timeout-ms");
            requirePositive(bingRssMaxResults, "oracle.news.bing-rss-max-results");
            requirePositive(googleRssTimeoutMs, "oracle.news.google-rss-timeout-ms");
            requirePositive(googleRssMaxResults, "oracle.news.google-rss-max-results");
            requireNonNegative(gdeltCooldownMs, "oracle.news.gdelt-cooldown-ms");
            requirePositive(wikipediaTimeoutMs, "oracle.news.wikipedia-timeout-ms");
            requirePositive(wikipediaMaxPages, "oracle.news.wikipedia-max-pages");
            requireNonNegative(wikipediaMaxCitations, "oracle.news.wikipedia-max-citations");
        }

        public static News defaults() {
            return new News(
                    true,
                    "https://www.bing.com",
                    8000L,
                    30,
                    true,
                    "https://news.google.com",
                    8000L,
                    30,
                    60000L,
                    true,
                    "https://en.wikipedia.org",
                    6000L,
                    3,
                    5
            );
        }
    }

                                private static void requirePositive(long value, String name) {
                                    if (value <= 0) {
                                        throw new IllegalArgumentException(name + " must be > 0, got " + value);
                                    }
                                }

                                private static void requireNonNegative(long value, String name) {
                                    if (value < 0) {
                                        throw new IllegalArgumentException(name + " must be >= 0, got " + value);
                                    }
                                }

                                private static void requirePositiveDouble(double value, String name) {
                                    if (!Double.isFinite(value) || value <= 0.0) {
                                        throw new IllegalArgumentException(
                                                name + " must be finite and > 0, got " + value);
                                    }
                                }

                                private static void requireNonNegativeDouble(double value, String name) {
                                    if (!Double.isFinite(value) || value < 0.0) {
                                        throw new IllegalArgumentException(
                                                name + " must be finite and >= 0, got " + value);
                                    }
                                }

                                private static void requireProbability(double value, String name) {
                                    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                                        throw new IllegalArgumentException(
                                                name + " must be in [0.0, 1.0], got " + value);
                                    }
                                }
}
