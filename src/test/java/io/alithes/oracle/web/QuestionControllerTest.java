package io.alithes.oracle.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.alithes.oracle.config.FetcherProperties;
import io.alithes.oracle.config.GdeltProperties;
import io.alithes.oracle.config.JevProperties;
import io.alithes.oracle.config.NewsProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.config.ScannerProperties;
import io.alithes.oracle.config.SecurityProperties;
import io.alithes.oracle.config.WebhookProperties;
import io.alithes.oracle.db.QuestionRepository;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.service.ScanScheduler;
import io.alithes.oracle.service.ScanService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestionControllerTest {

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
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private JdbcClient jdbcClient;

        @Autowired
        private QuestionRepository questionRepository;

        @Autowired
        private ApplicationContext applicationContext;

        private Instant now;
        private Instant windowStart;
        private Instant deadline;

        @BeforeEach
        void setUp() {
                WIRE_MOCK.resetAll();
                jdbcClient.sql("DELETE FROM questions").update();
                now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                windowStart = now.minus(Duration.ofDays(2));
                deadline = now.plus(Duration.ofDays(5));
        }

        @Test
        void postQuestionAcceptsValidQuestionAndGetByIdReturnsIt() throws Exception {
                stubAdmissionAccept();
                stubQueryCompilerPickFirst();

                String requestBody = """
                                {
                                  "text": "Has Country X officially announced a snap election?",
                                  "resolutionCriteria": "Official decree or head of government statement",
                                  "windowStart": "%s",
                                  "deadline": "%s",
                                  "graceHours": 24
                                }
                                """.formatted(windowStart, deadline);

                MvcResult createResult = mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isCreated())
                                .andExpect(header().string("Location", containsString("/v1/questions/")))
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.graceHours").value(24))
                                .andExpect(jsonPath("$.searchQueries").isArray())
                                .andExpect(jsonPath("$.searchQueries", hasSize(1)))
                                .andReturn();

                JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
                String id = created.path("id").asText();

                mockMvc.perform(get("/v1/questions/{id}", id))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(id))
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.text")
                                                .value("Has Country X officially announced a snap election?"));
        }

        @Test
        void postQuestionRejectsSubjectiveWith422AndPersistsRejectedQuestion() throws Exception {
                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
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
                                                                      "confidence": 0.90,
                                                                      "probabilities": {
                                                                        "discrete_event": 0.05,
                                                                        "numeric_threshold": 0.02,
                                                                        "subjective": 0.90,
                                                                        "unverifiable": 0.03
                                                                      }
                                                                    },
                                                                    "is_ambiguous": { "type": "noul", "noul": 0.80 },
                                                                    "is_newsworthy": { "type": "noul", "noul": 0.60 }
                                                                  },
                                                                  "usage": { "input_tokens": 90, "output_tokens": 12 }
                                                                }
                                                                """)));

                String requestBody = """
                                {
                                  "text": "Is the new monetary policy in Country X successful?",
                                  "windowStart": "%s",
                                  "deadline": "%s"
                                }
                                """.formatted(windowStart, deadline);

                MvcResult rejectResult = mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isUnprocessableEntity())
                                .andExpect(jsonPath("$.status").value(422))
                                .andExpect(jsonPath("$.reasons").isArray())
                                .andExpect(jsonPath("$.reasons", hasItem(containsString("subjective"))))
                                .andExpect(jsonPath("$.questionId").isNotEmpty())
                                .andReturn();

                JsonNode problem = objectMapper.readTree(rejectResult.getResponse().getContentAsString());
                UUID questionId = UUID.fromString(problem.path("questionId").asText());

                Question saved = questionRepository.findById(questionId).orElseThrow();
                assertThat(saved.status()).isEqualTo(QuestionStatus.REJECTED);

                mockMvc.perform(get("/v1/questions/{id}", questionId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("REJECTED"));
        }

        @Test
        void postQuestionRejectsInvalidSearchQueryOverrideAndOldWindowStart() throws Exception {
                stubAdmissionAccept();

                String badQueryBody = """
                                {
                                  "text": "Has Country X officially announced a snap election?",
                                  "windowStart": "%s",
                                  "deadline": "%s",
                                  "searchQueries": ["ab"]
                                }
                                """.formatted(windowStart, deadline);

                mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(badQueryBody))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));

                // Deterministic 80-day limit failure -> 422 without calling Jev
                String oldWindowBody = """
                                {
                                  "text": "Has Country X officially announced a snap election?",
                                  "windowStart": "%s",
                                  "deadline": "%s"
                                }
                                """.formatted(now.minus(Duration.ofDays(90)), deadline);

                mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(oldWindowBody))
                                .andExpect(status().isUnprocessableEntity())
                                .andExpect(jsonPath("$.reasons", hasItem(containsString("80 days"))));

                String excessiveGraceHoursBody = """
                                {
                                  "text": "Has Country X officially announced a snap election?",
                                  "windowStart": "%s",
                                  "deadline": "%s",
                                  "graceHours": 721
                                }
                                """.formatted(windowStart, deadline);

                mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(excessiveGraceHoursBody))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void unknownQuestionIdReturns404ProblemDetailAcrossEndpoints() throws Exception {
                UUID unknown = UUID.randomUUID();

                mockMvc.perform(get("/v1/questions/{id}", unknown))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));

                mockMvc.perform(get("/v1/questions/{id}/evidence", unknown))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));

                mockMvc.perform(get("/v1/questions/{id}/resolution", unknown))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));

                mockMvc.perform(post("/v1/questions/{id}/rescan", unknown))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));

                mockMvc.perform(post("/v1/questions/{id}/review", unknown)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"outcome\":\"RESOLVED_YES\",\"reason\":\"verified\"}"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        void rescanEvidenceStageFilterAndResolutionFlow() throws Exception {
                stubAdmissionAccept();
                stubQueryCompilerPickFirst();
                stubHeadlinePrefilter();
                stubMainAssessmentByTitleKeyword();

                String baseUrl = WIRE_MOCK.baseUrl();
                String seenIso = now.minus(Duration.ofHours(2)).toString().replace("-", "").replace(":", "");
                String publishedIso = now.minus(Duration.ofHours(3)).toString();

                WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo("/api/v2/doc/doc"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "articles": [
                                                                    {
                                                                      "url": "%s/articles/noise",
                                                                      "title": "Unrelated local sports tournament wraps up",
                                                                      "seendate": "%s",
                                                                      "domain": "example.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/c1",
                                                                      "title": "Country X confirms snap election for December",
                                                                      "seendate": "%s",
                                                                      "domain": "reuters.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/c2",
                                                                      "title": "Cabinet confirms Country X snap parliamentary vote",
                                                                      "seendate": "%s",
                                                                      "domain": "apnews.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/c3",
                                                                      "title": "Official decree confirms Country X early ballot",
                                                                      "seendate": "%s",
                                                                      "domain": "bbc.com"
                                                                    }
                                                                  ]
                                                                }
                                                                """
                                                                .formatted(baseUrl, seenIso, baseUrl, seenIso, baseUrl,
                                                                                seenIso, baseUrl, seenIso))));

                stubArticleHtml("/articles/c1", publishedIso,
                                "Reuters reports from the capital that Country X's prime minister signed the formal decree dissolving parliament and setting a snap election date.",
                                "Electoral authorities opened candidate registration immediately after the afternoon televised address from government headquarters.",
                                "Regional observers praised the clear schedule and confirmed international monitoring missions are preparing to arrive next week.");

                stubArticleHtml("/articles/c2", publishedIso,
                                "Associated Press correspondents confirmed that cabinet ministers unanimously endorsed the snap election proclamation during an emergency session.",
                                "Opposition parties held joint news briefings in major coastal provinces to announce primary slates and campaign finance commitments.",
                                "Economic analysts noted sovereign bond yields remained calm following reassurance of budget continuity during the caretaker phase.");

                stubArticleHtml("/articles/c3", publishedIso,
                                "BBC News verified through the official state gazette that citizens across all regions will cast ballots in the snap parliamentary vote.",
                                "Constitutional law professors explained the expedited calendar adheres strictly to statutory notice windows passed two years ago.",
                                "Civic coalitions launched voter information portals in response to surging public interest across urban and rural municipalities.");

                String createBody = """
                                {
                                  "text": "Has Country X officially announced a snap election?",
                                  "windowStart": "%s",
                                  "deadline": "%s"
                                }
                                """.formatted(windowStart, deadline);

                MvcResult createdResult = mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody))
                                .andExpect(status().isCreated())
                                .andReturn();

                String questionId = objectMapper.readTree(createdResult.getResponse().getContentAsString())
                                .path("id").asText();

                // Resolution returns 404 before any scan/resolution
                mockMvc.perform(get("/v1/questions/{id}/resolution", questionId))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));

                // Review returns 409 Conflict when question is ACTIVE
                mockMvc.perform(post("/v1/questions/{id}/review", questionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"outcome\":\"RESOLVED_YES\",\"reason\":\"not disputed yet\"}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.status").value(409));

                // Trigger synchronous rescan
                mockMvc.perform(post("/v1/questions/{id}/rescan", questionId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(questionId))
                                .andExpect(jsonPath("$.status").value("RESOLVED_YES"))
                                .andExpect(jsonPath("$.lastScanEnd").isNotEmpty());

                // Evidence endpoints: all (4), ASSESSED (3), SKIPPED (1)
                mockMvc.perform(get("/v1/questions/{id}/evidence", questionId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(4)));

                mockMvc.perform(get("/v1/questions/{id}/evidence", questionId)
                                .param("stage", "ASSESSED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(3)))
                                .andExpect(jsonPath("$[0].stage").value("ASSESSED"))
                                .andExpect(jsonPath("$[0].assessment").isNotEmpty())
                                .andExpect(jsonPath("$[0].assessment.pConfirms").isNumber());

                mockMvc.perform(get("/v1/questions/{id}/evidence", questionId)
                                .param("stage", "SKIPPED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(1)))
                                .andExpect(jsonPath("$[0].stage").value("SKIPPED"))
                                .andExpect(jsonPath("$[0].assessment").doesNotExist());

                // Resolution now returns 200 OK
                mockMvc.perform(get("/v1/questions/{id}/resolution", questionId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.questionId").value(questionId))
                                .andExpect(jsonPath("$.outcome").value("RESOLVED_YES"))
                                .andExpect(jsonPath("$.independentClusters").value(3));
        }

        @Test
        void manualReviewTransitionsDisputedQuestionToManualResolutionAndRejectsInvalidOutcome() throws Exception {
                stubAdmissionAccept();
                stubQueryCompilerPickFirst();
                stubHeadlinePrefilter();
                stubMainAssessmentByTitleKeyword();

                String baseUrl = WIRE_MOCK.baseUrl();
                String seenIso = now.minus(Duration.ofHours(2)).toString().replace("-", "").replace(":", "");
                String publishedIso = now.minus(Duration.ofHours(3)).toString();

                // 2 tier-1 confirming + 2 tier-1 denying -> confirmSum >= 1.50 and denySum >=
                // 1.50 -> DISPUTED
                WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo("/api/v2/doc/doc"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "articles": [
                                                                    {
                                                                      "url": "%s/articles/confirm-1",
                                                                      "title": "Country X confirms treaty ratification",
                                                                      "seendate": "%s",
                                                                      "domain": "reuters.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/confirm-2",
                                                                      "title": "Ministry confirms Country X signed accord",
                                                                      "seendate": "%s",
                                                                      "domain": "apnews.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/deny-1",
                                                                      "title": "Country X denies treaty ratification reports",
                                                                      "seendate": "%s",
                                                                      "domain": "bbc.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/deny-2",
                                                                      "title": "Speaker denies Country X approved accord",
                                                                      "seendate": "%s",
                                                                      "domain": "ft.com"
                                                                    }
                                                                  ]
                                                                }
                                                                """
                                                                .formatted(baseUrl, seenIso, baseUrl, seenIso, baseUrl,
                                                                                seenIso, baseUrl, seenIso))));

                stubArticleHtml("/articles/confirm-1", publishedIso,
                                "Officials in Country X announced the parliamentary ratification vote passed decisively following late-night floor debate.",
                                "Government spokespeople released signed parchment transcripts to diplomatic bureaus in Geneva and Brussels.",
                                "Trade associations welcomed immediate tariff reductions set to take effect early next month.");
                stubArticleHtml("/articles/confirm-2", publishedIso,
                                "Foreign ministry diplomats affirmed that Country X deposited final instruments of ratification before noon today.",
                                "Port operators prepared updated customs manifests covering maritime freight lanes across both treaty partners.",
                                "Lawmakers from the governing coalition hailed the accord as a turning point for regional commerce.");
                stubArticleHtml("/articles/deny-1", publishedIso,
                                "The presidential office in Country X issued an urgent statement denying that any ratification had occurred or been signed.",
                                "Parliamentary clerks clarified that the legislative docket remained suspended due to procedural quorum objections.",
                                "Legal advisors cautioned foreign partners against acting on premature wire dispatches circulating earlier.");
                stubArticleHtml("/articles/deny-2", publishedIso,
                                "The parliamentary speaker firmly rejected reports of treaty ratification, stating the bill has not reached a final floor vote.",
                                "Financial journalists reported currency markets reversed earlier gains after the official rebuttal broadcast.",
                                "Opposition whips confirmed committee amendments remain under review until next Tuesday.");

                String createBody = """
                                {
                                  "text": "Has Country X officially ratified the maritime accord?",
                                  "windowStart": "%s",
                                  "deadline": "%s"
                                }
                                """.formatted(windowStart, deadline);

                MvcResult createdResult = mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody))
                                .andExpect(status().isCreated())
                                .andReturn();

                String questionId = objectMapper.readTree(createdResult.getResponse().getContentAsString())
                                .path("id").asText();

                mockMvc.perform(post("/v1/questions/{id}/rescan", questionId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("DISPUTED"));

                // Invalid outcome value (ACTIVE is not allowed for manual review) -> 400 Bad
                // Request
                mockMvc.perform(post("/v1/questions/{id}/review", questionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"outcome\":\"ACTIVE\",\"reason\":\"invalid transition\"}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));

                // Unrecognized enum string -> 400 Bad Request
                mockMvc.perform(post("/v1/questions/{id}/review", questionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"outcome\":\"MAYBE\",\"reason\":\"bogus\"}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));

                // Valid manual review -> 200 OK
                mockMvc.perform(post("/v1/questions/{id}/review", questionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"outcome\":\"RESOLVED_NO\",\"reason\":\"Official gazette confirmed suspension\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.questionId").value(questionId))
                                .andExpect(jsonPath("$.outcome").value("RESOLVED_NO"))
                                .andExpect(jsonPath("$.model").value("manual"))
                                .andExpect(jsonPath("$.confidence").value(1.0))
                                .andExpect(jsonPath("$.details").value("Official gazette confirmed suspension"));

                mockMvc.perform(get("/v1/questions/{id}", questionId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("RESOLVED_NO"));

                // Subsequent review after leaving DISPUTED -> 409 Conflict
                mockMvc.perform(post("/v1/questions/{id}/review", questionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"outcome\":\"RESOLVED_YES\",\"reason\":\"retry\"}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void postQueryWithMinimalNaturalLanguageBodyResolvesYesAndReturnsEvidence() throws Exception {
                stubAdmissionAccept();
                stubQueryCompilerPickFirst();
                stubHeadlinePrefilter();
                stubMainAssessmentByTitleKeyword();

                String baseUrl = WIRE_MOCK.baseUrl();
                String seenIso = now.minus(Duration.ofHours(2)).toString().replace("-", "").replace(":", "");
                String publishedIso = now.minus(Duration.ofHours(3)).toString();

                WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo("/api/v2/doc/doc"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "articles": [
                                                                    {
                                                                      "url": "%s/articles/q-c1",
                                                                      "title": "Country X confirms snap election for December",
                                                                      "seendate": "%s",
                                                                      "domain": "reuters.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/q-c2",
                                                                      "title": "Cabinet confirms Country X snap parliamentary vote",
                                                                      "seendate": "%s",
                                                                      "domain": "apnews.com"
                                                                    },
                                                                    {
                                                                      "url": "%s/articles/q-c3",
                                                                      "title": "Official decree confirms Country X early ballot",
                                                                      "seendate": "%s",
                                                                      "domain": "bbc.com"
                                                                    }
                                                                  ]
                                                                }
                                                                """
                                                                .formatted(baseUrl, seenIso, baseUrl, seenIso, baseUrl,
                                                                                seenIso))));

                stubArticleHtml("/articles/q-c1", publishedIso,
                                "Reuters reports from the capital that Country X's prime minister signed the formal decree dissolving parliament and setting a snap election date.",
                                "Electoral authorities opened candidate registration immediately after the afternoon televised address from government headquarters.",
                                "Regional observers praised the clear schedule and confirmed international monitoring missions are preparing to arrive next week.");
                stubArticleHtml("/articles/q-c2", publishedIso,
                                "Associated Press correspondents confirmed that cabinet ministers unanimously endorsed the snap election proclamation during an emergency session.",
                                "Opposition parties held joint news briefings in major coastal provinces to announce primary slates and campaign finance commitments.",
                                "Economic analysts noted sovereign bond yields remained calm following reassurance of budget continuity during the caretaker phase.");
                stubArticleHtml("/articles/q-c3", publishedIso,
                                "BBC News verified through the official state gazette that citizens across all regions will cast ballots in the snap parliamentary vote.",
                                "Constitutional law professors explained the expedited calendar adheres strictly to statutory notice windows passed two years ago.",
                                "Civic coalitions launched voter information portals in response to surging public interest across urban and rural municipalities.");

                mockMvc.perform(post("/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "query": "Has Country X officially announced a snap election?"
                                                }
                                                """))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.questionId").isNotEmpty())
                                .andExpect(jsonPath("$.query")
                                                .value("Has Country X officially announced a snap election?"))
                                .andExpect(jsonPath("$.status").value("RESOLVED_YES"))
                                .andExpect(jsonPath("$.happened").value(true))
                                .andExpect(jsonPath("$.confidence").value(Matchers.greaterThanOrEqualTo(0.7)))
                                .andExpect(jsonPath("$.resolution.outcome").value("RESOLVED_YES"))
                                .andExpect(jsonPath("$.evidence", hasSize(3)));
        }

        @Test
        void postQueryWithZeroArticlesResolvesNoAndValidationRejectsOutOfRangeOrSubjective() throws Exception {
                stubAdmissionAccept();
                stubQueryCompilerPickFirst();
                WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo("/api/v2/doc/doc"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{}")));

                mockMvc.perform(post("/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "query": "Has Country X officially ratified the maritime accord?"
                                                }
                                                """))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("RESOLVED_NO"))
                                .andExpect(jsonPath("$.happened").value(false))
                                .andExpect(jsonPath("$.resolution.outcome").value("RESOLVED_NO"))
                                .andExpect(jsonPath("$.evidence", hasSize(0)));

                // Validation check: lookbackDays > 79 -> 400 Bad Request
                mockMvc.perform(post("/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "query": "Has Country X officially announced a snap election?",
                                                  "lookbackDays": 80
                                                }
                                                """))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status").value(400));

                // Subjective query -> 422 Unprocessable Entity with reasons
                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
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
                                                                      "confidence": 0.90,
                                                                      "probabilities": {
                                                                        "discrete_event": 0.05,
                                                                        "numeric_threshold": 0.02,
                                                                        "subjective": 0.90,
                                                                        "unverifiable": 0.03
                                                                      }
                                                                    },
                                                                    "is_ambiguous": { "type": "noul", "noul": 0.80 },
                                                                    "is_newsworthy": { "type": "noul", "noul": 0.60 }
                                                                  },
                                                                  "usage": { "input_tokens": 90, "output_tokens": 12 }
                                                                }
                                                                """)));

                mockMvc.perform(post("/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "query": "Is Country X's economic strategy good?"
                                                }
                                                """))
                                .andExpect(status().isUnprocessableEntity())
                                .andExpect(jsonPath("$.status").value(422))
                                .andExpect(jsonPath("$.reasons", hasItem(containsString("subjective"))));
        }

    @Test
    void scanSchedulerBeanIsDisabledByDefaultAndRegisteredOnlyWhenEnabled() {
        assertThat(applicationContext.getBeanNamesForType(ScanScheduler.class)).isEmpty();

        new ApplicationContextRunner()
                .withBean(OracleProperties.class, OracleProperties::defaults)
                .withBean(QuestionRepository.class, () -> Mockito.mock(QuestionRepository.class))
                .withBean(ScanService.class, () -> Mockito.mock(ScanService.class))
                .withUserConfiguration(ScanScheduler.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScanScheduler.class));

        new ApplicationContextRunner()
                .withPropertyValues("oracle.scanner.enabled=true")
                .withBean(OracleProperties.class, () -> new OracleProperties(
                        JevProperties.defaults(),
                        GdeltProperties.defaults(),
                        FetcherProperties.defaults(),
                        new ScannerProperties(true, 900000L, 30, 30, 0.30, 8),
                        WebhookProperties.defaults(),
                        PolicyProperties.defaults(),
                        SecurityProperties.defaults(),
                        NewsProperties.defaults()
                ))
                .withBean(QuestionRepository.class, () -> Mockito.mock(QuestionRepository.class))
                .withBean(ScanService.class, () -> Mockito.mock(ScanService.class))
                .withUserConfiguration(ScanScheduler.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(ScanScheduler.class));
    }

        @Test
        void consoleEndpointsListQuestionsScanLogsSystemStatusAndServeWebUi() throws Exception {
                stubAdmissionAccept();
                stubQueryCompilerPickFirst();

                String activeBody = """
                                {
                                  "text": "Has Country X officially announced a snap election?",
                                  "windowStart": "%s",
                                  "deadline": "%s"
                                }
                                """.formatted(windowStart, deadline);

                MvcResult firstCreated = mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(activeBody))
                                .andExpect(status().isCreated())
                                .andReturn();
                String firstId = objectMapper.readTree(firstCreated.getResponse().getContentAsString())
                                .path("id").asText();

                // Deterministic rejection (old windowStart) persists a REJECTED question after
                // firstCreated
                String rejectedBody = """
                                {
                                  "text": "Has Country X passed the border tariff bill?",
                                  "windowStart": "%s",
                                  "deadline": "%s"
                                }
                                """.formatted(now.minus(Duration.ofDays(90)), deadline);

                MvcResult secondCreated = mockMvc.perform(post("/v1/questions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rejectedBody))
                                .andExpect(status().isUnprocessableEntity())
                                .andReturn();
                String secondId = objectMapper.readTree(secondCreated.getResponse().getContentAsString())
                                .path("questionId").asText();

                // GET /v1/questions returns all questions newest first
                mockMvc.perform(get("/v1/questions"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(2)))
                                .andExpect(jsonPath("$[0].id").value(secondId))
                                .andExpect(jsonPath("$[1].id").value(firstId));

                // Status filtering
                mockMvc.perform(get("/v1/questions").param("status", "ACTIVE"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(1)))
                                .andExpect(jsonPath("$[0].id").value(firstId));

                mockMvc.perform(get("/v1/questions").param("status", "REJECTED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(1)))
                                .andExpect(jsonPath("$[0].id").value(secondId));

                // Trigger a rescan with empty GDELT result and inspect GET
                // /v1/questions/{id}/scans
                WIRE_MOCK.stubFor(WireMock.get(urlPathEqualTo("/api/v2/doc/doc"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{}")));

                mockMvc.perform(post("/v1/questions/{id}/rescan", firstId))
                                .andExpect(status().isOk());

                mockMvc.perform(get("/v1/questions/{id}/scans", firstId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(1)))
                                .andExpect(jsonPath("$[0].questionId").value(firstId))
                                .andExpect(jsonPath("$[0].gdeltOk").value(true))
                                .andExpect(jsonPath("$[0].articlesFound").value(0));

                mockMvc.perform(get("/v1/questions/{id}/scans", UUID.randomUUID()))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.status").value(404));

                // GET /v1/system exposes non-sensitive config & counts and never leaks the API
                // key
                MvcResult systemResult = mockMvc.perform(get("/v1/system"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.jevModel").value("jev-1.14.0"))
                                .andExpect(jsonPath("$.jevApiKeyConfigured").value(true))
                                .andExpect(jsonPath("$.schedulerEnabled").value(false))
                                .andExpect(jsonPath("$.policyVersion").value(1))
                                .andExpect(jsonPath("$.policy.version").value(1))
                                .andExpect(jsonPath("$.questionCounts.ACTIVE").value(1))
                                .andExpect(jsonPath("$.questionCounts.REJECTED").value(1))
                                .andReturn();
                assertThat(systemResult.getResponse().getContentAsString()).doesNotContain("test-key");

                // Bundled Web UI static assets
                mockMvc.perform(get("/"))
                                .andExpect(status().isOk());

                mockMvc.perform(get("/index.html"))
                                .andExpect(status().isOk())
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                                .string(containsString("AlithesOracle")));
        }

        private static void stubAdmissionAccept() {
                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
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
                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
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
                                                                        "q1": 0.85,
                                                                        "q2": 0.10,
                                                                        "q3": 0.03,
                                                                        "q4": 0.02
                                                                      }
                                                                    }
                                                                  },
                                                                  "usage": { "input_tokens": 70, "output_tokens": 5 }
                                                                }
                                                                """)));
        }

        private static void stubHeadlinePrefilter() {
                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
                                .withRequestBody(matchingJsonPath("$.state.headline"))
                                .atPriority(5)
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "model": "jev-1.14.0",
                                                                  "answers": {
                                                                    "relevant": { "type": "noul", "noul": 0.92 }
                                                                  },
                                                                  "usage": { "input_tokens": 50, "output_tokens": 4 }
                                                                }
                                                                """)));

                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
                                .withRequestBody(matchingJsonPath("$.state.headline", containing("Unrelated")))
                                .atPriority(1)
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("""
                                                                {
                                                                  "model": "jev-1.14.0",
                                                                  "answers": {
                                                                    "relevant": { "type": "noul", "noul": 0.10 }
                                                                  },
                                                                  "usage": { "input_tokens": 50, "output_tokens": 4 }
                                                                }
                                                                """)));
        }

        private static void stubMainAssessmentByTitleKeyword() {
                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
                                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                                .withRequestBody(matchingJsonPath("$.state.article.title", containing("denies")))
                                .atPriority(1)
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
                                                                      "choice": "denies_or_not_occurred",
                                                                      "confidence": 0.91,
                                                                      "probabilities": {
                                                                        "confirms_occurred": 0.01,
                                                                        "planned_or_future": 0.02,
                                                                        "speculation_or_rumor": 0.03,
                                                                        "denies_or_not_occurred": 0.94
                                                                      }
                                                                    },
                                                                    "stance_b": {
                                                                      "type": "choice",
                                                                      "choice": "denies_or_not_occurred",
                                                                      "confidence": 0.90,
                                                                      "probabilities": {
                                                                        "denies_or_not_occurred": 0.93,
                                                                        "speculation_or_rumor": 0.03,
                                                                        "planned_or_future": 0.02,
                                                                        "confirms_occurred": 0.02
                                                                      }
                                                                    },
                                                                    "is_recent": { "type": "noul", "noul": 0.95 },
                                                                    "attribution": {
                                                                      "type": "choice",
                                                                      "choice": "official_source_cited",
                                                                      "confidence": 0.92,
                                                                      "probabilities": {
                                                                        "official_source_cited": 0.91,
                                                                        "direct_reporting": 0.07,
                                                                        "anonymous_sources": 0.01,
                                                                        "opinion_or_analysis": 0.01
                                                                      }
                                                                    }
                                                                  },
                                                                  "usage": { "input_tokens": 250, "output_tokens": 28 }
                                                                }
                                                                """)));

                WIRE_MOCK.stubFor(WireMock.post(urlEqualTo("/v1/systemone"))
                                .withRequestBody(matchingJsonPath("$.questions.stance_a"))
                                .atPriority(5)
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
                WIRE_MOCK.stubFor(WireMock.get(urlEqualTo(path))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "text/html; charset=UTF-8")
                                                .withBody(html)));
        }
}
