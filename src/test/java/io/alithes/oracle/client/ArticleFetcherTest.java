package io.alithes.oracle.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.util.SafeUrlValidator;
import io.alithes.oracle.util.SimHash;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArticleFetcherTest {

    private WireMockServer wireMock;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private ArticleFetcher createFetcher(int maxBytes) {
        return createFetcher(maxBytes, true);
    }

    private ArticleFetcher createFetcher(int maxBytes, boolean allowPrivateNetworkUrls) {
        var props = new OracleProperties.Fetcher(
                5000L,
                maxBytes,
                "AlithesOracle-Test/1.0"
        );
        return new ArticleFetcher(props, httpClient, new SafeUrlValidator(allowPrivateNetworkUrls));
    }

    @Test
    void extractsArticleParagraphsMetadataStripsBoilerplateAndComputesHashes() {
        String p1 = "Prime Minister Jane Doe officially announced a snap parliamentary election on Thursday "
                + "morning following emergency cabinet talks in the capital.";
        String p2 = "The electoral commission confirmed that polling stations across all twelve regions will "
                + "open next month after parliament formally dissolves later today.";
        String p3 = "International observers and major political parties welcomed the timetable and pledged to "
                + "publish full campaign manifestos before Friday's deadline.";

        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta property="article:published_time" content="2026-11-22T14:30:00Z">
                  <script>var ignoreMe = "should be stripped completely";</script>
                  <style>body { color: black; }</style>
                </head>
                <body>
                  <nav><p>Navigation menu links that are longer than forty characters should still be ignored.</p></nav>
                  <header><p>Header banner text longer than forty characters must also be stripped out.</p></header>
                  <article>
                    <p>Short tag.</p>
                    <p>%s</p>
                    <p>%s</p>
                    <p>%s</p>
                    <script>alert('ignore this script');</script>
                  </article>
                  <footer><p>Footer copyright text longer than forty characters must be removed cleanly.</p></footer>
                </body>
                </html>
                """.formatted(p1, p2, p3);

        wireMock.stubFor(get(urlEqualTo("/world/election"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody(html)));

        FetchedArticle result = createFetcher(2_097_152)
                .fetch(wireMock.baseUrl() + "/world/election", "Snap election called");

        assertThat(result.status()).isEqualTo(FetchStatus.OK);
        assertThat(result.paragraphs()).containsExactly(p1, p2, p3);
        assertThat(result.joinedText())
                .contains(p1, p2, p3)
                .doesNotContain("Navigation menu")
                .doesNotContain("ignoreMe")
                .doesNotContain("Footer copyright")
                .doesNotContain("Short tag");
        assertThat(result.publishedAt()).isEqualTo(Instant.parse("2026-11-22T14:30:00Z"));
        assertThat(result.contentSha256()).hasSize(64);
        assertThat(result.excerpt()).isEqualTo(result.joinedText());
        assertThat(result.simhash()).isEqualTo(SimHash.compute(result.joinedText()));
        assertThat(result.simhash()).isNotZero();
    }

    @Test
    void returnsTitleOnlyOnHttp403PaywallWithoutThrowing() {
        wireMock.stubFor(get(urlEqualTo("/paywalled"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body><p>Subscription required.</p></body></html>")));

        FetchedArticle result = createFetcher(2_097_152)
                .fetch(wireMock.baseUrl() + "/paywalled", "Paywalled Headline Title");

        assertThat(result.status()).isEqualTo(FetchStatus.TITLE_ONLY);
        assertThat(result.paragraphs()).isEmpty();
        assertThat(result.joinedText()).isEqualTo("Paywalled Headline Title");
        assertThat(result.excerpt()).isEqualTo("Paywalled Headline Title");
        assertThat(result.publishedAt()).isNull();
        assertThat(result.contentSha256()).hasSize(64);
        assertThat(result.simhash()).isEqualTo(SimHash.compute("Paywalled Headline Title"));
    }

    @Test
    void returnsTitleOnlyWhenExtractedTextIsUnder300Chars() {
        String html = """
                <html><body>
                  <article>
                    <p>This single paragraph is over forty characters long, yet the entire article remains well below three hundred characters total.</p>
                  </article>
                </body></html>
                """;

        wireMock.stubFor(get(urlEqualTo("/short"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(html)));

        FetchedArticle result = createFetcher(2_097_152)
                .fetch(wireMock.baseUrl() + "/short", "Short Article Title");

        assertThat(result.status()).isEqualTo(FetchStatus.TITLE_ONLY);
        assertThat(result.excerpt()).isEqualTo("Short Article Title");
    }

    @Test
    void truncatesOversizedBodyAtMaxBytesWithoutOom() {
        String validLead = "Officials confirmed on Tuesday that the landmark trade agreement was signed in Geneva "
                + "after marathon ministerial talks covering tariffs, customs, and agricultural quotas across "
                + "both trading blocs. ".repeat(3);
        String giantTrailingParagraph = "Padding sentence repeated many times to simulate an oversized payload. "
                .repeat(4000);

        String oversizedHtml = "<html><body><article><p>" + validLead + "</p><p>"
                + giantTrailingParagraph + "</p></article></body></html>";

        wireMock.stubFor(get(urlEqualTo("/oversized"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody(oversizedHtml)));

        int maxBytes = 2048;
        FetchedArticle result = createFetcher(maxBytes)
                .fetch(wireMock.baseUrl() + "/oversized", "Oversized Trade Article");

        assertThat(result.status()).isEqualTo(FetchStatus.OK);
        assertThat(result.joinedText().length()).isLessThanOrEqualTo(maxBytes);
        assertThat(result.excerpt().length()).isLessThanOrEqualTo(2000);
        assertThat(result.joinedText()).contains("landmark trade agreement");
    }

    @Test
    void fallsBackToBodyParagraphsExtractsTimeInHeaderAndHandlesNonHtml() {
        String b1 = "Central bank policymakers voted unanimously to hold benchmark rates steady while "
                + "signaling potential adjustments later this quarter if inflation moderates.";
        String b2 = "Market analysts had widely anticipated the pause following mixed employment figures and "
                + "weaker manufacturing output across the region during October.";
        String b3 = "The governor scheduled a press conference for Thursday afternoon to outline updated "
                + "growth projections and liquidity measures for commercial lenders.";

        String bodyFallbackHtml = """
                <html>
                <body>
                  <header><time datetime="2026-11-22T14:30:00+02:00">Nov 22</time></header>
                  <main><p>Too short.</p></main>
                  <div class="story-body">
                    <p>%s</p>
                    <p>%s</p>
                    <p>%s</p>
                  </div>
                </body>
                </html>
                """.formatted(b1, b2, b3);

        wireMock.stubFor(get(urlEqualTo("/body-fallback"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(bodyFallbackHtml)));

        wireMock.stubFor(get(urlEqualTo("/report.pdf"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody("%PDF-1.4 fake pdf binary")));

        ArticleFetcher fetcher = createFetcher(2_097_152);

        FetchedArticle htmlResult = fetcher.fetch(wireMock.baseUrl() + "/body-fallback", "Rates Held Steady");
        assertThat(htmlResult.status()).isEqualTo(FetchStatus.OK);
        assertThat(htmlResult.paragraphs()).containsExactly(b1, b2, b3);
        assertThat(htmlResult.publishedAt()).isEqualTo(Instant.parse("2026-11-22T12:30:00Z"));

        FetchedArticle pdfResult = fetcher.fetch(wireMock.baseUrl() + "/report.pdf", "PDF Report Title");
        assertThat(pdfResult.status()).isEqualTo(FetchStatus.TITLE_ONLY);
        assertThat(pdfResult.excerpt()).isEqualTo("PDF Report Title");
    }

    @Test
    void followsUpToThreeRedirectsAndBlocksExcessiveHopsOrSsrfTargets() {
        String longBody = """
                <html><body><article>
                  <p>Official government gazette confirmed that the cabinet approved emergency trade regulations on Tuesday morning.</p>
                  <p>Customs authorities across major ports implemented the revised tariff schedule immediately after parliamentary notification.</p>
                  <p>Industry associations welcomed clarity on compliance timelines ahead of the winter shipping season.</p>
                </article></body></html>
                """;

        wireMock.stubFor(get(urlEqualTo("/hop-1"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/hop-2")));
        wireMock.stubFor(get(urlEqualTo("/hop-2"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/hop-3")));
        wireMock.stubFor(get(urlEqualTo("/hop-3"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", "/final-article")));
        wireMock.stubFor(get(urlEqualTo("/final-article"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(longBody)));

        // Exceeds MAX_REDIRECTS (3): 4 hops -> falls back to TITLE_ONLY
        wireMock.stubFor(get(urlEqualTo("/hop-0"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/hop-1")));

        ArticleFetcher permissiveFetcher = createFetcher(2_097_152, true);
        FetchedArticle okRedirect = permissiveFetcher.fetch(wireMock.baseUrl() + "/hop-1", "Redirected Title");
        assertThat(okRedirect.status()).isEqualTo(FetchStatus.OK);
        assertThat(okRedirect.joinedText()).contains("emergency trade regulations");

        FetchedArticle tooManyHops = permissiveFetcher.fetch(wireMock.baseUrl() + "/hop-0", "Loop Title");
        assertThat(tooManyHops.status()).isEqualTo(FetchStatus.TITLE_ONLY);

        // Strict SSRF mode blocks localhost & metadata redirects immediately without connecting
        ArticleFetcher strictFetcher = createFetcher(2_097_152, false);
        FetchedArticle blockedLocal = strictFetcher.fetch(wireMock.baseUrl() + "/final-article", "Blocked Local");
        assertThat(blockedLocal.status()).isEqualTo(FetchStatus.TITLE_ONLY);

        FetchedArticle blockedMetadata = strictFetcher.fetch(
                "http://169.254.169.254/latest/meta-data/", "Blocked Metadata");
        assertThat(blockedMetadata.status()).isEqualTo(FetchStatus.TITLE_ONLY);
    }
}
