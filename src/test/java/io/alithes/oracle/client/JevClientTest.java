package io.alithes.oracle.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.alithes.oracle.client.jev.ChoiceQuestion;
import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.NoulQuestion;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.config.JevProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JevClientTest {

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

    private JevClient createClient() {
        return createClient("jev-1.14.0", true, 3, 5L);
    }

    private JevClient createClient(String model, boolean rejectAlias, int maxRetries, long baseDelayMs) {
        var props = new JevProperties(
                wireMock.baseUrl(),
                "test-secret-key",
                model,
                rejectAlias,
                5000L,
                maxRetries,
                baseDelayMs
        );
        return new JevClient(props, httpClient, objectMapper);
    }

    @Test
    void rejectsAliasedModelsOnStartupWhenConfigured() {
        assertThatThrownBy(() -> createClient("jev-latest", true, 3, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jev-latest");

        assertThatThrownBy(() -> createClient("jev-preview", true, 3, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jev-preview");

        // Allowed when rejectAlias is false
        JevClient allowed = createClient("jev-latest", false, 3, 5L);
        assertThat(allowed).isNotNull();
    }

    @Test
    void evaluatesCombinedNoulAndChoiceQuestionsAndParsesResponse() {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .withHeader("Authorization", equalTo("Bearer test-secret-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "relevant": {
                                      "type": "noul",
                                      "noul": 0.94
                                    },
                                    "stance": {
                                      "type": "choice",
                                      "choice": "confirms_occurred",
                                      "confidence": 0.88,
                                      "probabilities": {
                                        "confirms_occurred": 0.91,
                                        "denies_or_not_occurred": 0.09
                                      }
                                    }
                                  },
                                  "usage": {
                                    "input_tokens": 312,
                                    "output_tokens": 14
                                  }
                                }
                                """)));

        JevClient client = createClient();

        Map<String, Object> state = Map.of(
                "question", "Has Country X announced a snap election?",
                "article", Map.of("title", "Country X calls snap vote", "paragraphs", List.of("Parliament dissolved."))
        );
        LinkedHashMap<String, String> stanceCriteria = new LinkedHashMap<>();
        stanceCriteria.put("confirms_occurred", "States as fact that it happened");
        stanceCriteria.put("denies_or_not_occurred", "States it did not happen");

        LinkedHashMap<String, QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("relevant", new NoulQuestion("Is this article about the election?"));
        questions.put("stance", new ChoiceQuestion("What does the article report?", stanceCriteria));

        JevResponse response = client.evaluate(state, questions);

        assertThat(response.model()).isEqualTo("jev-1.14.0");
        assertThat(response.inputTokens()).isEqualTo(312);
        assertThat(response.outputTokens()).isEqualTo(14);
        assertThat(response.requestSha256()).hasSize(64);
        assertThat(response.rawResponseBody()).contains("confirms_occurred");
        assertThat(response.noulProbability("relevant")).isEqualTo(0.94);
        assertThat(response.choice("stance")).isEqualTo("confirms_occurred");
        assertThat(response.choiceAnswer("stance").confidence()).isEqualTo(0.88);
        assertThat(response.choiceAnswer("stance").prob("confirms_occurred")).isEqualTo(0.91);
        assertThat(response.choiceAnswer("stance").probabilities())
                .containsEntry("confirms_occurred", 0.91)
                .containsEntry("denies_or_not_occurred", 0.09);
    }

    @Test
    void preservesChoiceCriteriaKeyInsertionOrderInSerializedJson() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "stance_a": {
                                      "type": "choice",
                                      "choice": "confirms_occurred",
                                      "confidence": 0.90,
                                      "probabilities": { "confirms_occurred": 0.90 }
                                    },
                                    "stance_b": {
                                      "type": "choice",
                                      "choice": "confirms_occurred",
                                      "confidence": 0.87,
                                      "probabilities": { "confirms_occurred": 0.87 }
                                    }
                                  },
                                  "usage": { "input_tokens": 100, "output_tokens": 10 }
                                }
                                """)));

        LinkedHashMap<String, String> forward = new LinkedHashMap<>();
        forward.put("confirms_occurred", "Occurred");
        forward.put("planned_or_future", "Planned");
        forward.put("speculation_or_rumor", "Rumor");
        forward.put("denies_or_not_occurred", "Denied");

        LinkedHashMap<String, String> reverse = new LinkedHashMap<>();
        reverse.put("denies_or_not_occurred", "Denied");
        reverse.put("speculation_or_rumor", "Rumor");
        reverse.put("planned_or_future", "Planned");
        reverse.put("confirms_occurred", "Occurred");

        LinkedHashMap<String, QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("stance_a", new ChoiceQuestion("Pick stance", forward));
        questions.put("stance_b", new ChoiceQuestion("Pick stance", reverse));

        createClient().evaluate(Map.of("question", "test"), questions);

        String sentBody = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        JsonNode root = objectMapper.readTree(sentBody);

        List<String> keysA = new ArrayList<>();
        root.path("questions").path("stance_a").path("criteria").fieldNames().forEachRemaining(keysA::add);
        List<String> keysB = new ArrayList<>();
        root.path("questions").path("stance_b").path("criteria").fieldNames().forEachRemaining(keysB::add);

        assertThat(keysA).containsExactly(
                "confirms_occurred", "planned_or_future", "speculation_or_rumor", "denies_or_not_occurred");
        assertThat(keysB).containsExactly(
                "denies_or_not_occurred", "speculation_or_rumor", "planned_or_future", "confirms_occurred");
        assertThat(root.path("questions").path("stance_a").path("type").asText()).isEqualTo("choice");
    }

    @Test
    void serializesNoulWithoutCriteriaOmittingNullAndRetriesOn5xx() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("server-err")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("ok")
                .willReturn(aResponse().withStatus(503).withBody("upstream unavailable")));

        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("server-err")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "q_noul": { "type": "noul", "noul": 0.62 }
                                  },
                                  "usage": { "input_tokens": 20, "output_tokens": 2 }
                                }
                                """)));

        JevResponse response = createClient().evaluate(
                "state",
                Map.of("q_noul", new NoulQuestion("Is this relevant?"))
        );

        assertThat(response.noulProbability("q_noul")).isEqualTo(0.62);
        String sentBody = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        JsonNode qNode = objectMapper.readTree(sentBody).path("questions").path("q_noul");
        assertThat(qNode.path("type").asText()).isEqualTo("noul");
        assertThat(qNode.has("criteria")).isFalse();
        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void retriesOn429WithRetryAfterZeroAndSucceedsOnSecondAttempt() {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0")
                        .withBody("{\"detail\":\"rate limited\"}")));

        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": { "ok": { "type": "noul", "noul": 0.85 } },
                                  "usage": { "input_tokens": 50, "output_tokens": 5 }
                                }
                                """)));

        JevResponse response = createClient().evaluate(
                "state",
                Map.of("ok", new NoulQuestion("Is it ok?"))
        );

        assertThat(response.noulProbability("ok")).isEqualTo(0.85);
        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void retriesOn529AndSucceedsOnSecondAttempt() {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("overloaded")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse()
                        .withStatus(529)
                        .withBody("{\"detail\":\"overloaded\"}")));

        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .inScenario("overloaded")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": { "ok": { "type": "noul", "noul": 0.77 } },
                                  "usage": { "input_tokens": 40, "output_tokens": 4 }
                                }
                                """)));

        JevResponse response = createClient().evaluate(
                "state",
                Map.of("ok", new NoulQuestion("Is it ok?"))
        );

        assertThat(response.noulProbability("ok")).isEqualTo(0.77);
        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void throwsJevContextTooLargeExceptionOn400MaxTokensExceededWithoutRetrying() {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"max_tokens_exceeded: 72000 > 64000\"}")));

        JevClient client = createClient();
        Map<String, QuestionSpec> questions = Map.of("q", new NoulQuestion("Check"));

        assertThatThrownBy(() -> client.evaluate("huge state", questions))
                .isInstanceOf(JevContextTooLargeException.class)
                .satisfies(ex -> {
                    var jevEx = (JevContextTooLargeException) ex;
                    assertThat(jevEx.statusCode()).isEqualTo(400);
                    assertThat(jevEx.responseBody()).contains("max_tokens_exceeded");
                });

        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void throwsJevExceptionOn401WithoutRetrying() {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"Invalid API key\"}")));

        JevClient client = createClient();
        Map<String, QuestionSpec> questions = Map.of("q", new NoulQuestion("Check"));

        assertThatThrownBy(() -> client.evaluate("state", questions))
                .isInstanceOf(JevException.class)
                .isNotInstanceOf(JevContextTooLargeException.class)
                .satisfies(ex -> assertThat(((JevException) ex).statusCode()).isEqualTo(401));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void rejectsOutOfRangeOrNonFiniteProbabilitiesFromJevResponse() {
        wireMock.stubFor(post(urlEqualTo("/v1/systemone"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "jev-1.14.0",
                                  "answers": {
                                    "q": { "type": "noul", "noul": 1.45 }
                                  },
                                  "usage": { "input_tokens": 10, "output_tokens": 1 }
                                }
                                """)));

        JevClient client = createClient();
        Map<String, QuestionSpec> questions = Map.of("q", new NoulQuestion("Check"));

        assertThatThrownBy(() -> client.evaluate("state", questions))
                .isInstanceOf(JevException.class);
    }
}
