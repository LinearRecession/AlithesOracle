package io.alithes.oracle.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.service.EvidenceAssessor;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WikipediaClientTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());
    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T11:00:00Z");

    static {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    private WikipediaClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        objectMapper = new ObjectMapper();
        var newsProps = new OracleProperties.News(
                true, "https://www.bing.com", 8000L, 30,
                true, "https://news.google.com", 8000L, 30,
                60000L,
                true, WIRE_MOCK.baseUrl(), 5000L, 3, 5
        );
        client = new WikipediaClient(
                newsProps,
                "AlithesOracle/1.0 (+https://alithes.io)",
                objectMapper,
                HttpClient.newHttpClient(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void searchesTopicAndPrimaryEntityDeduplicatesAndCapsToMaxPages() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("action", equalTo("query"))
                .withQueryParam("list", equalTo("search"))
                .withQueryParam("srsearch", containing("French"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "query": {
                                    "search": [
                                      {"title": "2027 French presidential election", "timestamp": "2026-11-20T09:00:00Z"},
                                      {"title": "2022 French presidential election", "timestamp": "2026-11-01T08:00:00Z"}
                                    ]
                                  }
                                }
                                """)));

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/w/api.php"))
                .withQueryParam("srsearch", equalTo("David Lisnard"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "query": {
                                    "search": [
                                      {"title": "David Lisnard", "timestamp": "2026-11-22T12:00:00Z"}
                                    ]
                                  }
                                }
                                """)));

        List<GdeltArticle> articles = client.searchQuestion(
                "Has David Lisnard officially announced his candidacy for the 2027 French presidential election?");

        assertThat(articles).hasSize(3);
        assertThat(articles).extracting(GdeltArticle::title).containsExactly(
                "2027 French presidential election",
                "2022 French presidential election",
                "David Lisnard"
        );
        assertThat(articles.get(0).url()).isEqualTo(WIRE_MOCK.baseUrl() + "/wiki/2027_French_presidential_election");
        assertThat(articles.get(0).seenDate()).isEqualTo(Instant.parse("2026-11-20T09:00:00Z"));
        assertThat(articles.get(2).url()).isEqualTo(WIRE_MOCK.baseUrl() + "/wiki/David_Lisnard");

        WIRE_MOCK.verify(2, getRequestedFor(urlPathEqualTo("/w/api.php")));
    }

    @Test
    void returnsEmptyWhenDisabledAndThrowsGdeltExceptionOnHttpError() {
        var disabledProps = new OracleProperties.News(
                true, "https://www.bing.com", 8000L, 30,
                true, "https://news.google.com", 8000L, 30,
                60000L,
                false, WIRE_MOCK.baseUrl(), 5000L, 3, 5
        );
        WikipediaClient disabled = new WikipediaClient(
                disabledProps, "AlithesOracle/1.0", objectMapper, HttpClient.newHttpClient(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        assertThat(disabled.searchQuestion("Has David Lisnard announced candidacy?")).isEmpty();
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo("/w/api.php")));

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        assertThatThrownBy(() -> client.searchQuestion("Has David Lisnard announced candidacy?"))
                .isInstanceOf(GdeltException.class)
                .hasMessageContaining("503");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evidenceAssessorSelectsLateKeywordMatchingParagraphPastIndex25() throws Exception {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("Lead summary paragraph about the 2027 French presidential election.");
        for (int i = 1; i <= 34; i++) {
            paragraphs.add("Unrelated historical background paragraph number " + i + " covering past parliamentary votes.");
        }
        String targetParagraph = "David Lisnard officially announced his candidacy for the 2027 French presidential election in Cannes.";
        paragraphs.add(targetParagraph);
        for (int i = 36; i < 42; i++) {
            paragraphs.add("Opinion polling table paragraph number " + i + ".");
        }

        Method selector = EvidenceAssessor.class.getDeclaredMethod(
                "selectScreeningCandidates", String.class, List.class);
        selector.setAccessible(true);
        List<String> selected = (List<String>) selector.invoke(
                null,
                "Has David Lisnard officially announced his candidacy for the 2027 French presidential election?",
                paragraphs);

        assertThat(selected).hasSize(25);
        assertThat(selected.get(0)).isEqualTo(paragraphs.get(0));
        assertThat(selected).contains(targetParagraph);
    }

    @Test
    void harvestsExternalNewsCitationsFromMatchingParagraphsOnlyAndDeduplicatesByDomain() {
        String pageUrl = WIRE_MOCK.baseUrl() + "/wiki/2027_French_presidential_election";
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/wiki/2027_French_presidential_election"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody("""
                                <html><body>
                                  <div id="mw-content-text">
                                    <p>Unrelated background paragraph about 1965 rules.<sup class="reference"><a href="#cite_note-1">[1]</a></sup></p>
                                    <p>Marine Le Pen announced her candidacy for the 2027 French presidential election.<sup class="reference"><a href="#cite_note-2">[2]</a></sup><sup class="reference"><a href="#cite_note-3">[3]</a></sup><sup class="reference"><a href="#cite_note-4">[4]</a></sup></p>
                                    <div class="reflist">
                                      <ol class="references">
                                        <li id="cite_note-1"><a class="external text" href="https://example.com/unrelated-1965">Unrelated 1965 Article</a></li>
                                        <li id="cite_note-2"><a class="external text" href="https://www.france24.com/en/france/le-pen-2027">Marine Le Pen declares 2027 French presidential bid</a></li>
                                        <li id="cite_note-3"><a class="external text" href="https://www.lemonde.fr/politique/le-pen-2027">Le Pen lance sa campagne pour 2027</a></li>
                                        <li id="cite_note-4">
                                          <a class="external text" href="https://twitter.com/mlp/status/123">Tweet</a>
                                          <a class="external text" href="https://www.france24.com/en/duplicate-domain">Duplicate France24 link</a>
                                        </li>
                                      </ol>
                                    </div>
                                  </div>
                                </body></html>
                                """)));

        List<GdeltArticle> wikiPages = List.of(new GdeltArticle(
                pageUrl, "2027 French presidential election", FIXED_NOW, "en.wikipedia.org", "English", "International"));

        List<GdeltArticle> harvested = client.harvestCitations(
                "Has Marine Le Pen officially announced her candidacy for the 2027 French presidential election?",
                wikiPages);

        assertThat(harvested).hasSize(2);
        assertThat(harvested).extracting(GdeltArticle::domain).containsExactly(
                "www.france24.com",
                "www.lemonde.fr"
        );
        assertThat(harvested).extracting(GdeltArticle::url).containsExactly(
                "https://www.france24.com/en/france/le-pen-2027",
                "https://www.lemonde.fr/politique/le-pen-2027"
        );
    }
}
