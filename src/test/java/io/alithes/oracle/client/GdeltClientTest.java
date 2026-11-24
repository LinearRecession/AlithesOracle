package io.alithes.oracle.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.alithes.oracle.config.GdeltProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GdeltClientTest {

    private WireMockServer wireMock;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        objectMapper = new ObjectMapper();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private GdeltClient createClient() {
        return createClient(0L, 3, 5L, 3);
    }

    private GdeltClient createClient(long minIntervalMs, int maxRetries, long baseDelayMs, int maxSplitDepth) {
        var props = new GdeltProperties(
                wireMock.baseUrl(),
                minIntervalMs,
                5000L,
                maxRetries,
                baseDelayMs,
                maxSplitDepth
        );
        return new GdeltClient(props, httpClient, objectMapper);
    }

    @Test
    void parsesValidResponseAndConvertsSeenDateToExactUtcInstant() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .withQueryParam("query", equalTo("\"Country X\" election"))
                .withQueryParam("mode", equalTo("artlist"))
                .withQueryParam("format", equalTo("json"))
                .withQueryParam("maxrecords", equalTo("250"))
                .withQueryParam("sort", equalTo("datedesc"))
                .withQueryParam("startdatetime", equalTo("20261122000000"))
                .withQueryParam("enddatetime", equalTo("20261122160000"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "articles": [
                                    {
                                      "url": "https://www.reuters.com/world/example-1/",
                                      "url_mobile": "",
                                      "title": "Country X Prime Minister Calls Snap Election",
                                      "seendate": "20261122T143000Z",
                                      "domain": "reuters.com",
                                      "language": "English",
                                      "sourcecountry": "United Kingdom"
                                    },
                                    {
                                      "url": "https://apnews.com/article/example-2",
                                      "title": "Opposition Welcomes Early Vote",
                                      "seendate": "20261122T151545Z",
                                      "domain": "apnews.com",
                                      "language": "English",
                                      "sourcecountry": "United States"
                                    }
                                  ]
                                }
                                """)));

        List<GdeltArticle> articles = createClient().search(
                "\"Country X\" election",
                Instant.parse("2026-11-22T00:00:00Z"),
                Instant.parse("2026-11-22T16:00:00Z")
        );

        assertThat(articles).hasSize(2);
        assertThat(articles.get(0).url()).isEqualTo("https://www.reuters.com/world/example-1/");
        assertThat(articles.get(0).title()).isEqualTo("Country X Prime Minister Calls Snap Election");
        assertThat(articles.get(0).seenDate()).isEqualTo(Instant.parse("2026-11-22T14:30:00Z"));
        assertThat(articles.get(0).domain()).isEqualTo("reuters.com");
        assertThat(articles.get(0).language()).isEqualTo("English");
        assertThat(articles.get(0).sourceCountry()).isEqualTo("United Kingdom");
        assertThat(articles.get(1).seenDate()).isEqualTo(Instant.parse("2026-11-22T15:15:45Z"));
    }

    @Test
    void returnsEmptyListForEmptyJsonObjectOrEmptyArticles() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .inScenario("empty-variants")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("empty-array")
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .inScenario("empty-variants")
                .whenScenarioStateIs("empty-array")
                .willReturn(aResponse().withStatus(200).withBody("{\"articles\": []}")));

        GdeltClient client = createClient();
        Instant from = Instant.parse("2026-11-22T00:00:00Z");
        Instant to = Instant.parse("2026-11-22T02:00:00Z");

        assertThat(client.search("rare event query", from, to)).isEmpty();
        assertThat(client.search("rare event query", from, to)).isEmpty();
    }

    @Test
    void enforcesMinimumIntervalBetweenConsecutiveHttpRequests() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        GdeltClient rateLimitedClient = createClient(120L, 1, 5L, 3);
        Instant from = Instant.parse("2026-11-22T00:00:00Z");
        Instant to = Instant.parse("2026-11-22T02:00:00Z");

        long startNanos = System.nanoTime();
        rateLimitedClient.search("election", from, to);
        rateLimitedClient.search("election", from, to);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(110L);
    }

    @Test
    void throwsGdeltQueryExceptionOnHttp200PlainTextErrorWithoutRetrying() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("Your query was too short or too general.")));

        GdeltClient client = createClient();

        assertThatThrownBy(() -> client.search(
                "ab",
                Instant.parse("2026-11-22T00:00:00Z"),
                Instant.parse("2026-11-22T02:00:00Z")
        ))
                .isInstanceOf(GdeltQueryException.class)
                .hasMessageContaining("Your query was too short");

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/v2/doc/doc")));
    }

    @Test
    void retriesOnHttp429RateLimitAndSucceeds() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .inScenario("gdelt-429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("ok")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("Please limit requests to one every 5 seconds.")));

        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .inScenario("gdelt-429")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("""
                                {
                                  "articles": [
                                    {
                                      "url": "https://bbc.com/news/1",
                                      "title": "Rate limit recovered",
                                      "seendate": "20261122T120000Z",
                                      "domain": "bbc.com",
                                      "language": "English",
                                      "sourcecountry": "United Kingdom"
                                    }
                                  ]
                                }
                                """)));

        List<GdeltArticle> articles = createClient().search(
                "election",
                Instant.parse("2026-11-22T00:00:00Z"),
                Instant.parse("2026-11-22T02:00:00Z")
        );

        assertThat(articles).hasSize(1);
        assertThat(articles.get(0).url()).isEqualTo("https://bbc.com/news/1");
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/api/v2/doc/doc")));
    }

    @Test
    void splitsTimeWindowInHalfWhen250ArticlesReturnedAndDeduplicatesByUrl() throws Exception {
        String fullWindowBody = buildArticlesJson("https://example.com/full/", 250);
        String leftWindowBody = objectMapper.writeValueAsString(Map.of("articles", List.of(
                Map.of(
                        "url", "https://reuters.com/left-1",
                        "title", "Left 1",
                        "seendate", "20261122T010000Z",
                        "domain", "reuters.com",
                        "language", "English",
                        "sourcecountry", "UK"
                ),
                Map.of(
                        "url", "https://apnews.com/boundary-shared",
                        "title", "Shared at Midpoint",
                        "seendate", "20261122T020000Z",
                        "domain", "apnews.com",
                        "language", "English",
                        "sourcecountry", "US"
                )
        )));
        String rightWindowBody = objectMapper.writeValueAsString(Map.of("articles", List.of(
                Map.of(
                        "url", "https://apnews.com/boundary-shared",
                        "title", "Shared at Midpoint",
                        "seendate", "20261122T020000Z",
                        "domain", "apnews.com",
                        "language", "English",
                        "sourcecountry", "US"
                ),
                Map.of(
                        "url", "https://bbc.com/right-1",
                        "title", "Right 1",
                        "seendate", "20261122T030000Z",
                        "domain", "bbc.com",
                        "language", "English",
                        "sourcecountry", "UK"
                )
        )));

        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .withQueryParam("startdatetime", equalTo("20261122000000"))
                .withQueryParam("enddatetime", equalTo("20261122040000"))
                .willReturn(aResponse().withStatus(200).withBody(fullWindowBody)));

        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .withQueryParam("startdatetime", equalTo("20261122000000"))
                .withQueryParam("enddatetime", equalTo("20261122020000"))
                .willReturn(aResponse().withStatus(200).withBody(leftWindowBody)));

        wireMock.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .withQueryParam("startdatetime", equalTo("20261122020000"))
                .withQueryParam("enddatetime", equalTo("20261122040000"))
                .willReturn(aResponse().withStatus(200).withBody(rightWindowBody)));

        List<GdeltArticle> articles = createClient().search(
                "snap election",
                Instant.parse("2026-11-22T00:00:00Z"),
                Instant.parse("2026-11-22T04:00:00Z")
        );

        assertThat(articles)
                .extracting(GdeltArticle::url)
                .containsExactly(
                        "https://reuters.com/left-1",
                        "https://apnews.com/boundary-shared",
                        "https://bbc.com/right-1"
                );
        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/api/v2/doc/doc")));
    }

    @Test
    void sanitizerNormalizesDropsShortTokensWrapsOrAndCapsLength() {
        assertThat(GdeltQuerySanitizer.sanitize("Is it an election in France?"))
                .isEqualTo("election France");

        assertThat(GdeltQuerySanitizer.sanitize("election OR vote"))
                .isEqualTo("(election OR vote)");

        assertThat(GdeltQuerySanitizer.sanitize("“European Central Bank” interest OR rates!"))
                .isEqualTo("\"European Central Bank\" (interest OR rates)");

        assertThat(GdeltQuerySanitizer.sanitize("\"European Central Bank\" (interest OR rates)"))
                .isEqualTo("\"European Central Bank\" (interest OR rates)");

        assertThat(GdeltQuerySanitizer.sanitize("domain:bbc sourcelang:eng snap OR emergency"))
                .isEqualTo("domain:bbc sourcelang:eng (snap OR emergency)");

        String longInput = "(" + "alpha OR bravo OR charlie OR delta OR echo OR foxtrot OR golf OR hotel OR "
                + "india OR juliet OR kilo OR lima OR mike OR november OR oscar OR papa OR quebec OR "
                + "romeo OR sierra OR tango OR uniform OR victor OR whiskey OR xray OR yankee OR zulu)";
        String truncated = GdeltQuerySanitizer.sanitize(longInput);
        assertThat(truncated.length()).isLessThanOrEqualTo(200);
        assertThat(countChar(truncated, '(')).isEqualTo(countChar(truncated, ')'));
        assertThat(countChar(truncated, '"') % 2).isZero();
        assertThat(truncated).doesNotEndWith(" OR").doesNotEndWith(" AND");

        assertThatThrownBy(() -> GdeltQuerySanitizer.sanitize("a b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GdeltQuerySanitizer.sanitize("EU OR UK"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String buildArticlesJson(String urlPrefix, int count) throws Exception {
        List<Map<String, String>> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(Map.of(
                    "url", urlPrefix + i,
                    "title", "Article " + i,
                    "seendate", "20261122T010000Z",
                    "domain", "example.com",
                    "language", "English",
                    "sourcecountry", "US"
            ));
        }
        return objectMapper.writeValueAsString(Map.of("articles", items));
    }

    private static int countChar(String s, char target) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }
}
