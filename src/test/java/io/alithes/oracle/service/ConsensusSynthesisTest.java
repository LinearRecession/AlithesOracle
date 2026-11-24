package io.alithes.oracle.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.alithes.oracle.db.QuestionRepository;
import io.alithes.oracle.db.ResolutionRepository;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "oracle.policy.jev-synthesis-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConsensusSynthesisTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @DynamicPropertySource
    static void overrideServiceUrls(DynamicPropertyRegistry registry) {
        registry.add("oracle.jev.base-url", WIRE_MOCK::baseUrl);
        registry.add("oracle.gdelt.base-url", WIRE_MOCK::baseUrl);
    }

    @Autowired
    private QuestionService questionService;

    @Autowired
    private ScanService scanService;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private ResolutionRepository resolutionRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private Instant now;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        jdbcClient.sql("DELETE FROM questions").update();
        now = Instant.now();
    }

    @Test
    void promotesPostDeadlinePartialEvidenceDisputedToResolvedYesWhenSynthesisIsConfident() {
        stubAdmissionAndQuery();
        stubHeadlinePrefilter(0.90);
        stubTitleOnlyConfirmingAssessment();

        // 4 TITLE_ONLY tier-1 articles after deadline -> each w ~ 0.34 (< 0.55), confirmSum ~ 1.36 (>= 1.00) -> DISPUTED before synthesis
        stubGdeltFourConfirmingArticles();
        stubSynthesis(0.95, 0.02, 0.92);

        Question q = questionService.createQuestion(
                "Is Jensen Huang the current CEO of nvidia?",
                null,
                now.minus(Duration.ofDays(3)),
                now.minus(Duration.ofHours(1)),
                0,
                null,
                null
        ).question();

        scanService.scanQuestion(q.id());

        Question updated = questionRepository.findById(q.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(QuestionStatus.RESOLVED_YES);

        Resolution resolution = resolutionRepository.findByQuestionId(q.id()).orElseThrow();
        assertThat(resolution.outcome()).isEqualTo(QuestionStatus.RESOLVED_YES);
        assertThat(resolution.details()).contains("Jev consensus synthesis confirmed YES");
        assertThat(resolution.confidence()).isGreaterThanOrEqualTo(0.85);
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.consensus_yes")));
    }

    @Test
    void keepsDisputedWhenSynthesisSufficiencyIsLow() {
        stubAdmissionAndQuery();
        stubHeadlinePrefilter(0.90);
        stubTitleOnlyConfirmingAssessment();
        stubGdeltFourConfirmingArticles();
        stubSynthesis(0.72, 0.15, 0.40);

        Question q = questionService.createQuestion(
                "Is Jensen Huang the current CEO of nvidia?",
                null,
                now.minus(Duration.ofDays(3)),
                now.minus(Duration.ofHours(1)),
                0,
                null,
                null
        ).question();

        scanService.scanQuestion(q.id());

        Question updated = questionRepository.findById(q.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(QuestionStatus.DISPUTED);
    }

    @Test
    void doesNotInvokeSynthesisOnTrueContradiction() {
        stubAdmissionAndQuery();
        stubHeadlinePrefilter(0.90);

        String baseUrl = WIRE_MOCK.baseUrl();
        String seenIso = now.minus(Duration.ofHours(2)).toString().replace("-", "").replace(":", "").substring(0, 15) + "Z";
        String publishedIso = now.minus(Duration.ofHours(2)).toString();

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "articles": [
                                    {"url": "%s/a1", "title": "Reuters confirms vote passed", "seendate": "%s", "domain": "reuters.com"},
                                    {"url": "%s/a2", "title": "AP confirms vote passed", "seendate": "%s", "domain": "apnews.com"},
                                    {"url": "%s/d1", "title": "BBC denies vote passed", "seendate": "%s", "domain": "bbc.com"},
                                    {"url": "%s/d2", "title": "FT denies vote passed", "seendate": "%s", "domain": "ft.com"}
                                  ]
                                }
                                """.formatted(baseUrl, seenIso, baseUrl, seenIso, baseUrl, seenIso, baseUrl, seenIso))));

        stubArticleHtml("/a1", publishedIso,
                "Reuters correspondents in the national capital confirmed that lawmakers voted decisively in a late session to approve the parliamentary bill.",
                "Government clerks immediately published the signed legislative transcripts and official voting records to accredited international press bureaus.",
                "Trade associations and regional port operators welcomed the immediate customs and tariff reductions scheduled to take effect early next week.");
        stubArticleHtml("/a2", publishedIso,
                "Associated Press reporters affirmed that cabinet ministers unanimously endorsed and countersigned the enacted maritime statute before noon today.",
                "Harbor authorities and shipping regulators prepared revised cargo inspection manifests covering major northern and coastal trade corridors.",
                "Coalition leaders praised broad bipartisan cooperation throughout the extended floor debate and final roll-call certification process.");
        stubArticleHtml("/d1", publishedIso,
                "BBC News broadcast an urgent statement from the speaker's office flatly denying that any legislative vote passed or reached the floor.",
                "Opposition whips clarified to journalists that formal committee quorum objections suspended the parliamentary docket until next month.",
                "Constitutional legal scholars cautioned international trade partners against acting on premature or unverified wire dispatches circulating earlier.");
        stubArticleHtml("/d2", publishedIso,
                "Financial Times confirmed the prime minister publicly rejected reports of passage, emphasizing that no binding floor tally occurred today.",
                "Sovereign bond markets and currency desks reversed earlier gains immediately following the televised official government rebuttal broadcast.",
                "Senior parliamentary clerks verified that outstanding committee amendments remain pending review and final reading is postponed until Tuesday.");

        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                .withRequestBody(matchingJsonPath("$.state.article.title", com.github.tomakehurst.wiremock.client.WireMock.containing("denies")))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(assessmentJson("denies_or_not_occurred", 0.02, 0.94))));

        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(assessmentJson("confirms_occurred", 0.94, 0.02))));

        Question q = questionService.createQuestion(
                "Has Country X passed the parliamentary vote?",
                null,
                now.minus(Duration.ofDays(3)),
                now.minus(Duration.ofHours(1)),
                0,
                null,
                null
        ).question();

        scanService.scanQuestion(q.id());

        assertThat(questionRepository.findById(q.id()).orElseThrow().status())
                .isEqualTo(QuestionStatus.DISPUTED);
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.consensus_yes")));
    }

    private static void stubArticleHtml(String path, String publishedIso, String p1, String p2, String p3) {
        WIRE_MOCK.stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("""
                                <html><head><meta property="article:published_time" content="%s"></head>
                                <body><article><p>%s</p><p>%s</p><p>%s</p></article></body></html>
                                """.formatted(publishedIso, p1, p2, p3))));
    }

    private void stubGdeltFourConfirmingArticles() {
        String baseUrl = WIRE_MOCK.baseUrl();
        String seenIso = now.minus(Duration.ofHours(2)).toString().replace("-", "").replace(":", "").substring(0, 15) + "Z";
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "articles": [
                                    {"url": "%s/t1", "title": "Nvidia reinstates Jensen Huang as CEO", "seendate": "%s", "domain": "reuters.com"},
                                    {"url": "%s/t2", "title": "Jensen Huang back as Nvidia CEO", "seendate": "%s", "domain": "apnews.com"},
                                    {"url": "%s/t3", "title": "Nvidia board names Jensen Huang CEO again", "seendate": "%s", "domain": "bbc.com"},
                                    {"url": "%s/t4", "title": "Jensen Huang returns to lead Nvidia as CEO", "seendate": "%s", "domain": "nytimes.com"}
                                  ]
                                }
                                """.formatted(baseUrl, seenIso, baseUrl, seenIso, baseUrl, seenIso, baseUrl, seenIso))));
        for (String p : new String[]{"/t1", "/t2", "/t3", "/t4"}) {
            WIRE_MOCK.stubFor(get(urlEqualTo(p)).willReturn(aResponse().withStatus(404)));
        }
    }

    private static void stubAdmissionAndQuery() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.question_type"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "question_type": {
                                      "type": "choice",
                                      "choice": "discrete_event",
                                      "confidence": 0.95,
                                      "probabilities": {"discrete_event": 0.95, "numeric_threshold": 0.02, "subjective": 0.02, "unverifiable": 0.01}
                                    },
                                    "is_ambiguous": {"type": "noul", "noul": 0.10},
                                    "is_newsworthy": {"type": "noul", "noul": 0.95}
                                  },
                                  "usage": {"input_tokens": 50, "output_tokens": 10}
                                }
                                """)));

        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.best_query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "best_query": {
                                      "type": "choice",
                                      "choice": "q1",
                                      "confidence": 0.90,
                                      "probabilities": {"q1": 0.90, "q2": 0.05}
                                    }
                                  },
                                  "usage": {"input_tokens": 40, "output_tokens": 5}
                                }
                                """)));
    }

    private static void stubHeadlinePrefilter(double score) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.state.headline"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {"relevant": {"type": "noul", "noul": %s}},
                                  "usage": {"input_tokens": 30, "output_tokens": 4}
                                }
                                """.formatted(score))));
    }

    private static void stubTitleOnlyConfirmingAssessment() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(assessmentJson("confirms_occurred", 0.96, 0.01))));
    }

    private static void stubSynthesis(double pYes, double pNo, double pSufficient) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.consensus_yes"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "consensus_yes": {"type": "noul", "noul": %s},
                                    "consensus_no": {"type": "noul", "noul": %s},
                                    "consensus_clear": {"type": "noul", "noul": %s}
                                  },
                                  "usage": {"input_tokens": 120, "output_tokens": 10}
                                }
                                """.formatted(pYes, pNo, pSufficient))));
    }

    private static String assessmentJson(String choice, double pConfirms, double pDenies) {
        return """
                {
                  "model": "jev-1.14.0",
                  "answers": {
                    "relevant": {"type": "noul", "noul": 0.95},
                    "stance_fits": {"type": "noul", "noul": 0.95},
                    "stance_a": {
                      "type": "choice",
                      "choice": "%s",
                      "confidence": 0.92,
                      "probabilities": {"confirms_occurred": %s, "planned_or_future": 0.02, "speculation_or_rumor": 0.01, "denies_or_not_occurred": %s}
                    },
                    "stance_b": {
                      "type": "choice",
                      "choice": "%s",
                      "confidence": 0.91,
                      "probabilities": {"denies_or_not_occurred": %s, "speculation_or_rumor": 0.01, "planned_or_future": 0.02, "confirms_occurred": %s}
                    },
                    "is_recent": {"type": "noul", "noul": 0.95},
                    "attribution": {
                      "type": "choice",
                      "choice": "official_source_cited",
                      "confidence": 0.92,
                      "probabilities": {"official_source_cited": 0.92, "direct_reporting": 0.06, "anonymous_sources": 0.01, "opinion_or_analysis": 0.01}
                    }
                  },
                  "usage": {"input_tokens": 150, "output_tokens": 20}
                }
                """.formatted(choice, pConfirms, pDenies, choice, pDenies, pConfirms);
    }
}
