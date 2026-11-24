package io.alithes.oracle.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
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

class GoogleNewsRssClientTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());
    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T10:00:00Z");

    static {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    private GoogleNewsRssClient client;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        var newsProps = new OracleProperties.News(
                true,
                "https://www.bing.com",
                3000L,
                10,
                true,
                WIRE_MOCK.baseUrl(),
                3000L,
                10,
                60000L,
                true,
                "https://en.wikipedia.org",
                6000L,
                3,
                5
        );
        client = new GoogleNewsRssClient(
                newsProps,
                "AlithesOracle/1.0",
                HttpClient.newHttpClient(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void parsesRssItemsExtractsSourceDomainCleansTitleAndFiltersWindow() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/rss/search"))
                .withQueryParam("q", containing("after:2026-11-18"))
                .withQueryParam("q", containing("before:2026-11-25"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml; charset=UTF-8")
                        .withBody("""
                                <?xml version="1.0" encoding="UTF-8"?>
                                <rss version="2.0">
                                  <channel>
                                    <item>
                                      <title>Nvidia announces leadership update - Reuters</title>
                                      <link>https://news.google.com/rss/articles/item-1</link>
                                      <pubDate>Sun, 22 Nov 2026 14:30:00 GMT</pubDate>
                                      <source url="https://www.reuters.com">Reuters</source>
                                    </item>
                                    <item>
                                      <title>Old news from last year - BBC</title>
                                      <link>https://news.google.com/rss/articles/item-old</link>
                                      <pubDate>Mon, 20 Oct 2025 10:00:00 GMT</pubDate>
                                      <source url="https://www.bbc.com">BBC</source>
                                    </item>
                                    <item>
                                      <title>Mobile subdomain report - AP News</title>
                                      <link>https://news.google.com/rss/articles/item-2</link>
                                      <pubDate>Mon, 23 Nov 2026 09:15:00 GMT</pubDate>
                                      <source url="https://m.apnews.com">AP News</source>
                                    </item>
                                  </channel>
                                </rss>
                                """)));

        Instant from = Instant.parse("2026-11-19T00:00:00Z");
        Instant to = Instant.parse("2026-11-24T10:00:00Z");

        List<GdeltArticle> articles = client.search("\"Nvidia\" leadership", from, to);

        assertThat(articles).hasSize(2);
        assertThat(articles.get(0).url()).isEqualTo("https://news.google.com/rss/articles/item-1");
        assertThat(articles.get(0).title()).isEqualTo("Nvidia announces leadership update");
        assertThat(articles.get(0).domain()).isEqualTo("reuters.com");
        assertThat(articles.get(0).seenDate()).isEqualTo(Instant.parse("2026-11-22T14:30:00Z"));

        assertThat(articles.get(1).domain()).isEqualTo("apnews.com");
        assertThat(articles.get(1).title()).isEqualTo("Mobile subdomain report");

        WIRE_MOCK.verify(1, getRequestedFor(urlPathEqualTo("/rss/search")));
    }

    @Test
    void returnsEmptyListForFeedWithNoItems() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/rss/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel></channel></rss>")));

        List<GdeltArticle> articles = client.search(
                "nvidia",
                FIXED_NOW.minusSeconds(3600),
                FIXED_NOW
        );

        assertThat(articles).isEmpty();
    }

    @Test
    void throwsGdeltExceptionOnHttpError() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/rss/search"))
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        assertThatThrownBy(() -> client.search("nvidia", FIXED_NOW.minusSeconds(3600), FIXED_NOW))
                .isInstanceOf(GdeltException.class)
                .hasMessageContaining("503");
    }
}
