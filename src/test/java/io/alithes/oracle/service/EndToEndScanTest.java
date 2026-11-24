package io.alithes.oracle.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.alithes.oracle.db.AssessmentRepository;
import io.alithes.oracle.db.EvidenceRepository;
import io.alithes.oracle.db.QuestionRepository;
import io.alithes.oracle.db.ResolutionRepository;
import io.alithes.oracle.db.ScanLogRepository;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.util.SafeUrlValidator;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EndToEndScanTest {

    private static final Instant BASELINE_NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String TEST_WEBHOOK_SECRET = "test-webhook-secret";
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

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(BASELINE_NOW, ZoneOffset.UTC);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant current;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.current = initial;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.current = instant;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private QuestionService questionService;

    @Autowired
    private ScanService scanService;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private ResolutionRepository resolutionRepository;

    @Autowired
    private ScanLogRepository scanLogRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        jdbcClient.sql("DELETE FROM questions").update();
        clock.setInstant(BASELINE_NOW);
    }

    @Test
    void subjectiveQuestionIsRejectedAndPersistedInDatabase() {
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
                                      "choice": "subjective",
                                      "confidence": 0.91,
                                      "probabilities": {
                                        "discrete_event": 0.04,
                                        "numeric_threshold": 0.02,
                                        "subjective": 0.91,
                                        "unverifiable": 0.03
                                      }
                                    },
                                    "is_ambiguous": { "type": "noul", "noul": 0.88 },
                                    "is_newsworthy": { "type": "noul", "noul": 0.55 }
                                  },
                                  "usage": { "input_tokens": 90, "output_tokens": 12 }
                                }
                                """)));

        Instant windowStart = BASELINE_NOW.minus(Duration.ofDays(2));
        Instant deadline = BASELINE_NOW.plus(Duration.ofDays(7));

        QuestionService.CreateQuestionResult result = questionService.createQuestion(
                "Is Country X's current economic policy better than last year's?",
                null,
                windowStart,
                deadline,
                48,
                null,
                null
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).isNotEmpty();
        assertThat(result.question().status()).isEqualTo(QuestionStatus.REJECTED);
        assertThat(result.question().searchQueries()).isEmpty();

        Question persisted = questionRepository.findById(result.question().id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(QuestionStatus.REJECTED);
        assertThat(persisted.statusReason()).contains("subjective");
        assertThat(persisted.searchQueries()).isEmpty();
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo("/api/v2/doc/doc")));
    }

    @Test
    void activeQuestionWithThreeTierOneConfirmingArticlesResolvesYesAndDeliversSignedWebhook() throws Exception {
        stubAdmissionAccept();
        stubQueryCompilerPickFirst();
        stubHeadlinePrefilterScore(0.92);
        stubMainAssessmentConfirming();

        String baseUrl = WIRE_MOCK.baseUrl();
        String article1Url = baseUrl + "/articles/reuters-1";
        String article2Url = baseUrl + "/articles/ap-2";
        String article3Url = baseUrl + "/articles/bbc-3";

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "articles": [
                                    {
                                      "url": "%s",
                                      "title": "Country X Prime Minister announces snap parliamentary election",
                                      "seendate": "20261122T100000Z",
                                      "domain": "reuters.com",
                                      "language": "English",
                                      "sourcecountry": "United Kingdom"
                                    },
                                    {
                                      "url": "%s",
                                      "title": "Early vote scheduled after Country X dissolves legislature",
                                      "seendate": "20261122T103000Z",
                                      "domain": "apnews.com",
                                      "language": "English",
                                      "sourcecountry": "United States"
                                    },
                                    {
                                      "url": "%s",
                                      "title": "Snap election decree signed in Country X capital",
                                      "seendate": "20261122T110000Z",
                                      "domain": "bbc.com",
                                      "language": "English",
                                      "sourcecountry": "United Kingdom"
                                    }
                                  ]
                                }
                                """.formatted(article1Url, article2Url, article3Url))));

        stubArticleHtml("/articles/reuters-1", "2026-11-22T10:00:00Z",
                "Prime Minister Jane Doe addressed parliament on Sunday morning and formally proclaimed a snap national election for next month following coalition talks.",
                "Cabinet ministers endorsed the dissolution decree immediately after the televised address from the government palace in the capital city.",
                "Electoral officials confirmed voter registration rolls will close Friday ahead of official campaign rallies.");

        stubArticleHtml("/articles/ap-2", "2026-11-22T10:30:00Z",
                "Legislators in Country X adjourned indefinitely today after the head of government invoked constitutional powers to trigger early nationwide balloting.",
                "Opposition leaders held emergency strategy briefings while international monitors announced plans to deploy observation teams across coastal provinces.",
                "Financial markets steadied as treasury officials pledged uninterrupted public spending during the interim caretaker period.");

        stubArticleHtml("/articles/bbc-3", "2026-11-22T11:00:00Z",
                "An official gazette published in the capital confirmed that the snap parliamentary poll will proceed on December 20 after the prime minister's announcement.",
                "Constitutional scholars noted this marks the fastest election timetable in three decades, prompting civic groups to organize televised debates.",
                "Regional ambassadors welcomed the transparent schedule and urged peaceful participation from all registered political factions.");

        WIRE_MOCK.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        Instant windowStart = BASELINE_NOW.minus(Duration.ofDays(1));
        Instant deadline = BASELINE_NOW.plus(Duration.ofDays(3));
        String webhookUrl = baseUrl + "/webhook";

        QuestionService.CreateQuestionResult created = questionService.createQuestion(
                "Has Country X officially announced a snap election?",
                "Confirmed by official decree or prime minister announcement",
                windowStart,
                deadline,
                48,
                null,
                webhookUrl
        );

        assertThat(created.accepted()).isTrue();
        Question q = created.question();
        assertThat(q.status()).isEqualTo(QuestionStatus.ACTIVE);
        assertThat(q.searchQueries()).isNotEmpty();

        clock.advance(Duration.ofMinutes(15));
        scanService.scanQuestion(q.id());

        Question updated = questionRepository.findById(q.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(QuestionStatus.RESOLVED_YES);
        assertThat(updated.lastScanEnd()).isNotNull();

        Resolution resolution = resolutionRepository.findByQuestionId(q.id()).orElseThrow();
        assertThat(resolution.outcome()).isEqualTo(QuestionStatus.RESOLVED_YES);
        assertThat(resolution.independentClusters()).isEqualTo(3);
        assertThat(resolution.supportingEvidenceIds()).hasSize(3);
        assertThat(resolution.confirmSum()).isGreaterThanOrEqualTo(2.20);
        assertThat(resolution.model()).isEqualTo("jev-1.14.0");

        List<Evidence> evidenceList = evidenceRepository.findByQuestionId(q.id());
        assertThat(evidenceList).hasSize(3);
        assertThat(evidenceList).allMatch(ev -> ev.stage() == EvidenceStage.ASSESSED);
        assertThat(assessmentRepository.findByQuestionId(q.id())).hasSize(3);

        List<ScanLog> logs = scanLogRepository.findByQuestionId(q.id());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).gdeltOk()).isTrue();
        assertThat(logs.get(0).articlesFound()).isEqualTo(3);
        assertThat(logs.get(0).newEvidence()).isEqualTo(3);

        List<LoggedRequest> webhookRequests = WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/webhook")));
        assertThat(webhookRequests).hasSize(1);
        LoggedRequest webhookReq = webhookRequests.get(0);
        assertThat(webhookReq.getHeader("Content-Type")).contains("application/json");

        String signature = webhookReq.getHeader("X-Alithes-Signature");
        assertThat(signature).startsWith("sha256=");
        String expectedSig = "sha256=" + computeHmacSha256Hex(TEST_WEBHOOK_SECRET, webhookReq.getBody());
        assertThat(signature).isEqualTo(expectedSig);

        JsonNode webhookJson = objectMapper.readTree(webhookReq.getBodyAsString());
        assertThat(webhookJson.path("questionId").asText()).isEqualTo(q.id().toString());
        assertThat(webhookJson.path("outcome").asText()).isEqualTo("RESOLVED_YES");
        assertThat(webhookJson.path("independentClusters").asInt()).isEqualTo(3);
    }

    @Test
    void activeQuestionWithZeroArticlesTransitionsToResolvedNoAfterDeadlinePlusGrace() {
        stubAdmissionAccept();
        stubQueryCompilerPickFirst();

        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        Instant windowStart = BASELINE_NOW.minus(Duration.ofDays(1));
        Instant deadline = BASELINE_NOW.plus(Duration.ofDays(1));

        QuestionService.CreateQuestionResult created = questionService.createQuestion(
                "Has Country X formally ratified the maritime treaty?",
                null,
                windowStart,
                deadline,
                48,
                null,
                null
        );

        assertThat(created.accepted()).isTrue();
        Question q = created.question();
        assertThat(q.status()).isEqualTo(QuestionStatus.ACTIVE);

        // Scan before deadline + grace: stays ACTIVE
        clock.advance(Duration.ofHours(6));
        scanService.scanQuestion(q.id());
        assertThat(questionRepository.findById(q.id()).orElseThrow().status())
                .isEqualTo(QuestionStatus.ACTIVE);
        assertThat(resolutionRepository.findByQuestionId(q.id())).isEmpty();

        // Advance past deadline + 48h grace (finalAt = BASELINE_NOW + 3 days)
        clock.setInstant(q.finalAt().plus(Duration.ofHours(1)));
        scanService.scanQuestion(q.id());

        Question resolvedQuestion = questionRepository.findById(q.id()).orElseThrow();
        assertThat(resolvedQuestion.status()).isEqualTo(QuestionStatus.RESOLVED_NO);

        Resolution resolution = resolutionRepository.findByQuestionId(q.id()).orElseThrow();
        assertThat(resolution.outcome()).isEqualTo(QuestionStatus.RESOLVED_NO);
        assertThat(resolution.confirmSum()).isZero();
        assertThat(resolution.confidence()).isGreaterThanOrEqualTo(0.50);
    }

    @Test
    void evidenceAssessorScreensLongArticlesVerifiesStanceOrderAndRetriesOnContextTooLarge() throws Exception {
        stubAdmissionAccept();
        stubQueryCompilerPickFirst();
        stubHeadlinePrefilterScore(0.90);

        // Long article (> 8 paragraphs) triggers paragraph screening, and main assessment returns 400 max_tokens_exceeded once
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.state.paragraphs"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "p0": { "type": "noul", "noul": 0.90 },
                                    "p1": { "type": "noul", "noul": 0.20 },
                                    "p2": { "type": "noul", "noul": 0.85 },
                                    "p3": { "type": "noul", "noul": 0.15 },
                                    "p4": { "type": "noul", "noul": 0.10 },
                                    "p5": { "type": "noul", "noul": 0.75 },
                                    "p6": { "type": "noul", "noul": 0.10 },
                                    "p7": { "type": "noul", "noul": 0.05 },
                                    "p8": { "type": "noul", "noul": 0.05 },
                                    "p9": { "type": "noul", "noul": 0.05 }
                                  },
                                  "usage": { "input_tokens": 180, "output_tokens": 20 }
                                }
                                """)));

        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("context-retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                .willSetStateTo("retried")
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"max_tokens_exceeded\"}")));

        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("context-retry")
                .whenScenarioStateIs("retried")
                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "relevant": { "type": "noul", "noul": 0.91 },
                                    "stance_fits": { "type": "noul", "noul": 0.93 },
                                    "stance_a": {
                                      "type": "choice",
                                      "choice": "confirms_occurred",
                                      "confidence": 0.89,
                                      "probabilities": {
                                        "confirms_occurred": 0.90,
                                        "planned_or_future": 0.05,
                                        "speculation_or_rumor": 0.03,
                                        "denies_or_not_occurred": 0.02
                                      }
                                    },
                                    "stance_b": {
                                      "type": "choice",
                                      "choice": "confirms_occurred",
                                      "confidence": 0.85,
                                      "probabilities": {
                                        "denies_or_not_occurred": 0.03,
                                        "speculation_or_rumor": 0.04,
                                        "planned_or_future": 0.07,
                                        "confirms_occurred": 0.86
                                      }
                                    },
                                    "is_recent": { "type": "noul", "noul": 0.92 },
                                    "attribution": {
                                      "type": "choice",
                                      "choice": "direct_reporting",
                                      "confidence": 0.88,
                                      "probabilities": {
                                        "official_source_cited": 0.10,
                                        "direct_reporting": 0.85,
                                        "anonymous_sources": 0.03,
                                        "opinion_or_analysis": 0.02
                                      }
                                    }
                                  },
                                  "usage": { "input_tokens": 220, "output_tokens": 30 }
                                }
                                """)));

        String baseUrl = WIRE_MOCK.baseUrl();
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "articles": [
                                    {
                                      "url": "%s/articles/long-story",
                                      "title": "Country X announces snap election in sweeping address",
                                      "seendate": "20261122T100000Z",
                                      "domain": "reuters.com",
                                      "language": "English",
                                      "sourcecountry": "United Kingdom"
                                    }
                                  ]
                                }
                                """.formatted(baseUrl))));

        StringBuilder paragraphsHtml = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            paragraphsHtml.append("<p>Paragraph ").append(i)
                    .append(" provides extensive background context about Country X and the election decree issued today in the capital. </p>");
        }
        WIRE_MOCK.stubFor(get(urlEqualTo("/articles/long-story"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody("""
                                <html><head><meta property="article:published_time" content="2026-11-22T10:00:00Z"></head>
                                <body><article>%s</article></body></html>
                                """.formatted(paragraphsHtml))));

        Question q = questionService.createQuestion(
                "Has Country X officially announced a snap election?",
                null,
                BASELINE_NOW.minus(Duration.ofDays(1)),
                BASELINE_NOW.plus(Duration.ofDays(3)),
                48,
                null,
                null
        ).question();

        scanService.scanQuestion(q.id());

        List<Evidence> evidenceList = evidenceRepository.findByQuestionId(q.id());
        assertThat(evidenceList).hasSize(1);
        assertThat(evidenceList.get(0).stage()).isEqualTo(EvidenceStage.ASSESSED);

        var assessments = assessmentRepository.findByQuestionId(q.id());
        assertThat(assessments).hasSize(1);
        assertThat(assessments.get(0).pConfirms()).isEqualTo(0.88);
        assertThat(assessments.get(0).stanceUnstable()).isFalse();
        assertThat(assessments.get(0).stanceConfidence()).isEqualTo(0.85);

        // Verify stance_a forward order and stance_b exact reverse order in Jev request payload
        List<LoggedRequest> assessReqs = WIRE_MOCK.findAll(
                postRequestedFor(urlEqualTo("/v1/systemone"))
                        .withRequestBody(matchingJsonPath("$.questions.stance_a"))
        );
        assertThat(assessReqs).hasSize(2);
        JsonNode firstAssess = objectMapper.readTree(assessReqs.get(0).getBodyAsString());
        JsonNode retryAssess = objectMapper.readTree(assessReqs.get(1).getBodyAsString());

        List<String> stanceAKeys = new ArrayList<>();
        firstAssess.path("questions").path("stance_a").path("criteria").fieldNames().forEachRemaining(stanceAKeys::add);
        List<String> stanceBKeys = new ArrayList<>();
        firstAssess.path("questions").path("stance_b").path("criteria").fieldNames().forEachRemaining(stanceBKeys::add);

        assertThat(stanceAKeys).containsExactly(
                "confirms_occurred", "planned_or_future", "speculation_or_rumor", "denies_or_not_occurred");
        assertThat(stanceBKeys).containsExactly(
                "denies_or_not_occurred", "speculation_or_rumor", "planned_or_future", "confirms_occurred");

        // Screened call selected p0, p2, p5 (3 paragraphs); retry call capped at <= 3 paragraphs and <= 1500 chars
        assertThat(retryAssess.path("state").path("article").path("paragraphs").size()).isLessThanOrEqualTo(3);
    }

    @Test
    void concurrentScanCallsDoNotDuplicateAssessmentsAndStrictWebhookBlocksPrivateNetwork() throws Exception {
        stubAdmissionAccept();
        stubQueryCompilerPickFirst();
        stubHeadlinePrefilterScore(0.92);
        stubMainAssessmentConfirming();

        String baseUrl = WIRE_MOCK.baseUrl();
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/api/v2/doc/doc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "articles": [
                                    {
                                      "url": "%s/articles/concurrent-1",
                                      "title": "Country X announces snap election",
                                      "seendate": "20261122T100000Z",
                                      "domain": "reuters.com"
                                    }
                                  ]
                                }
                                """.formatted(baseUrl))));

        stubArticleHtml("/articles/concurrent-1", "2026-11-22T10:00:00Z",
                "Prime Minister Jane Doe announced a snap election on Sunday morning.",
                "Cabinet ministers ratified the decree immediately after the televised address.",
                "Electoral authorities opened candidate registration across all provinces.");

        Question q = questionService.createQuestion(
                "Has Country X officially announced a snap election?",
                null,
                BASELINE_NOW.minus(Duration.ofDays(1)),
                BASELINE_NOW.plus(Duration.ofDays(3)),
                48,
                null,
                null
        ).question();

        CountDownLatch startGate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> f1 = pool.submit(() -> {
                startGate.await();
                scanService.scanQuestion(q.id());
                return null;
            });
            Future<?> f2 = pool.submit(() -> {
                startGate.await();
                scanService.scanQuestion(q.id());
                return null;
            });
            startGate.countDown();
            f1.get();
            f2.get();
        }

        assertThat(evidenceRepository.findByQuestionId(q.id())).hasSize(1);
        assertThat(assessmentRepository.findByQuestionId(q.id())).hasSize(1);

        // Strict WebhookNotifier refuses loopback/private URLs without making an HTTP request
        WIRE_MOCK.stubFor(post(urlEqualTo("/strict-webhook")).willReturn(aResponse().withStatus(200)));
        WebhookNotifier strictNotifier = new WebhookNotifier(
                io.alithes.oracle.config.OracleProperties.defaults(),
                HttpClient.newHttpClient(),
                objectMapper,
                new SafeUrlValidator(false)
        );
        Resolution dummyRes = new Resolution(
                UUID.randomUUID(),
                QuestionStatus.RESOLVED_YES,
                0.9,
                2.5,
                0.0,
                3,
                List.of(),
                1,
                "jev-1.14.0",
                "ok",
                BASELINE_NOW
        );
        strictNotifier.notify(baseUrl + "/strict-webhook", dummyRes);
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/strict-webhook")));
    }

    private static void stubAdmissionAccept() {
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
                                      "confidence": 0.94,
                                      "probabilities": {
                                        "discrete_event": 0.93,
                                        "numeric_threshold": 0.03,
                                        "subjective": 0.02,
                                        "unverifiable": 0.02
                                      }
                                    },
                                    "is_ambiguous": { "type": "noul", "noul": 0.12 },
                                    "is_newsworthy": { "type": "noul", "noul": 0.91 }
                                  },
                                  "usage": { "input_tokens": 80, "output_tokens": 10 }
                                }
                                """)));
    }

    private static void stubQueryCompilerPickFirst() {
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
                                      "confidence": 0.86,
                                      "probabilities": {
                                        "q1": 0.82,
                                        "q2": 0.12,
                                        "q3": 0.04,
                                        "q4": 0.02
                                      }
                                    }
                                  },
                                  "usage": { "input_tokens": 70, "output_tokens": 5 }
                                }
                                """)));
    }

    private static void stubHeadlinePrefilterScore(double score) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.state.headline"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "relevant": { "type": "noul", "noul": %s }
                                  },
                                  "usage": { "input_tokens": 50, "output_tokens": 4 }
                                }
                                """.formatted(score))));
    }

    private static void stubMainAssessmentConfirming() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "relevant": { "type": "noul", "noul": 0.96 },
                                    "stance_fits": { "type": "noul", "noul": 0.95 },
                                    "stance_a": {
                                      "type": "choice",
                                      "choice": "confirms_occurred",
                                      "confidence": 0.91,
                                      "probabilities": {
                                        "confirms_occurred": 0.94,
                                        "planned_or_future": 0.03,
                                        "speculation_or_rumor": 0.02,
                                        "denies_or_not_occurred": 0.01
                                      }
                                    },
                                    "stance_b": {
                                      "type": "choice",
                                      "choice": "confirms_occurred",
                                      "confidence": 0.89,
                                      "probabilities": {
                                        "denies_or_not_occurred": 0.02,
                                        "speculation_or_rumor": 0.02,
                                        "planned_or_future": 0.04,
                                        "confirms_occurred": 0.92
                                      }
                                    },
                                    "is_recent": { "type": "noul", "noul": 0.95 },
                                    "attribution": {
                                      "type": "choice",
                                      "choice": "official_source_cited",
                                      "confidence": 0.92,
                                      "probabilities": {
                                        "official_source_cited": 0.90,
                                        "direct_reporting": 0.08,
                                        "anonymous_sources": 0.01,
                                        "opinion_or_analysis": 0.01
                                      }
                                    }
                                  },
                                  "usage": { "input_tokens": 250, "output_tokens": 28 }
                                }
                                """)));
    }

    private static void stubArticleHtml(String path, String publishedIso, String p1, String p2, String p3) {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta property="article:published_time" content="%s">
                </head>
                <body>
                  <article>
                    <p>%s</p>
                    <p>%s</p>
                    <p>%s</p>
                  </article>
                </body>
                </html>
                """.formatted(publishedIso, p1, p2, p3);
        WIRE_MOCK.stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBody(html)));
    }

    private static String computeHmacSha256Hex(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
