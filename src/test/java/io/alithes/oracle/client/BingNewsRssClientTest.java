package io.alithes.oracle.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.alithes.oracle.config.OracleProperties;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BingNewsRssClientTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());
    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T10:00:00Z");

    static {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    private BingNewsRssClient client;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        var newsProps = new OracleProperties.News(
                true,
                WIRE_MOCK.baseUrl(),
                3000L,
                10,
                true,
                "https://news.google.com",
                3000L,
                10,
                60000L,
                true,
                "https://en.wikipedia.org",
                6000L,
                3,
                5
        );
        client = new BingNewsRssClient(
                newsProps,
                "AlithesOracle/1.0",
                HttpClient.newHttpClient(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void extractsDirectPublisherUrlAndDomainAndFiltersOutStalePubDates() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/news/search"))
                .withQueryParam("format", equalTo("rss"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml; charset=UTF-8")
                        .withBody("""
                                <?xml version="1.0" encoding="utf-8"?>
                                <rss version="2.0" xmlns:News="https://www.bing.com/news/search">
                                  <channel>
                                    <item>
                                      <title>Nvidia CEO Jensen Huang pulls off an unlikely comeback</title>
                                      <link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;aid=&amp;tid=123&amp;url=https%3a%2f%2fwww.reuters.com%2fworld%2fnvidia-ceo-2026%2f&amp;c=99&amp;mkt=en-ww</link>
                                      <pubDate>Mon, 23 Nov 2026 13:15:00 GMT</pubDate>
                                      <News:Source>Reuters</News:Source>
                                    </item>
                                    <item>
                                      <title>Stale article from last year</title>
                                      <link>http://www.bing.com/news/apiclick.aspx?url=https%3a%2f%2fwww.bbc.com%2fnews%2fold</link>
                                      <pubDate>Mon, 20 Oct 2025 10:00:00 GMT</pubDate>
                                    </item>
                                    <item>
                                      <title>Mobile link fallback</title>
                                      <link>https://m.apnews.com/article/nvidia-ceo</link>
                                      <pubDate>Tue, 24 Nov 2026 08:00:00 GMT</pubDate>
                                    </item>
                                  </channel>
                                </rss>
                                """)));

        Instant from = Instant.parse("2026-11-20T00:00:00Z");
        Instant to = Instant.parse("2026-11-24T10:00:00Z");

        List<GdeltArticle> articles = client.search("Jensen Huang Nvidia CEO", from, to);

        assertThat(articles).hasSize(2);
        assertThat(articles.get(0).url()).isEqualTo("https://www.reuters.com/world/nvidia-ceo-2026/");
        assertThat(articles.get(0).domain()).isEqualTo("reuters.com");
        assertThat(articles.get(0).title()).isEqualTo("Nvidia CEO Jensen Huang pulls off an unlikely comeback");
        assertThat(articles.get(0).seenDate()).isEqualTo(Instant.parse("2026-11-23T13:15:00Z"));

        assertThat(articles.get(1).url()).isEqualTo("https://m.apnews.com/article/nvidia-ceo");
        assertThat(articles.get(1).domain()).isEqualTo("apnews.com");

        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo("/news/search")));
    }

    @Test
    void returnsEmptyListForEmptyFeedAndThrowsOnHttpError() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/news/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel></channel></rss>")));

        assertThat(client.search("nvidia", FIXED_NOW.minusSeconds(3600), FIXED_NOW)).isEmpty();

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/news/search"))
                .willReturn(aResponse().withStatus(503).withBody("Unavailable")));

        assertThatThrownBy(() -> client.search("nvidia", FIXED_NOW.minusSeconds(3600), FIXED_NOW))
                .isInstanceOf(GdeltException.class)
                .hasMessageContaining("503");
    }
}
