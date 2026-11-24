package io.alithes.oracle.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request and response DTOs for the TypeSafe AI Jev {@code /v1/systemone} API.
 */
public final class JevModels {

    private JevModels() {
    }

    public record SystemOneRequest(
            String model,
            Object state,
            Map<String, QuestionSpec> questions
    ) {
        public SystemOneRequest {
            questions = questions == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(questions));
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = NoulQuestion.class, name = "noul"),
            @JsonSubTypes.Type(value = ChoiceQuestion.class, name = "choice")
    })
    public sealed interface QuestionSpec permits NoulQuestion, ChoiceQuestion {
        String instructions();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoulQuestion(
            String instructions,
            Map<String, String> criteria
    ) implements QuestionSpec {
        @JsonCreator
        public NoulQuestion {
            if (criteria != null) {
                criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
            }
        }

        public NoulQuestion(String instructions) {
            this(instructions, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChoiceQuestion(
            String instructions,
            LinkedHashMap<String, String> criteria
    ) implements QuestionSpec {
        @JsonCreator
        public ChoiceQuestion {
            criteria = criteria == null ? new LinkedHashMap<>() : new LinkedHashMap<>(criteria);
        }

        public ChoiceQuestion(String instructions, Map<String, String> criteria) {
            this(instructions, criteria == null ? new LinkedHashMap<>() : new LinkedHashMap<>(criteria));
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = NoulAnswer.class, name = "noul"),
            @JsonSubTypes.Type(value = ChoiceAnswer.class, name = "choice")
    })
    public sealed interface Answer permits NoulAnswer, ChoiceAnswer {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoulAnswer(double noul) implements Answer {
        public NoulAnswer {
            if (!Double.isFinite(noul) || noul < 0.0 || noul > 1.0) {
                throw new IllegalArgumentException("Noul probability must be in [0.0, 1.0], got " + noul);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChoiceAnswer(
            String choice,
            double confidence,
            Map<String, Double> probabilities
    ) implements Answer {
        public ChoiceAnswer {
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("Choice confidence must be in [0.0, 1.0], got " + confidence);
            }
            if (probabilities == null) {
                probabilities = Map.of();
            } else {
                LinkedHashMap<String, Double> validated = new LinkedHashMap<>(probabilities.size());
                for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
                    Double val = entry.getValue();
                    if (val == null || !Double.isFinite(val) || val < 0.0 || val > 1.0) {
                        throw new IllegalArgumentException(
                                "Choice probability for '" + entry.getKey() + "' must be in [0.0, 1.0], got " + val);
                    }
                    validated.put(entry.getKey(), val);
                }
                probabilities = Collections.unmodifiableMap(validated);
            }
        }

        public double prob(String option) {
            return probabilities.getOrDefault(option, 0.0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SystemOneResponse(
            String model,
            Map<String, Answer> answers,
            Usage usage
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Usage(
                @JsonProperty("input_tokens") int inputTokens,
                @JsonProperty("output_tokens") int outputTokens
        ) {
        }
    }

    public record JevResponse(
            String model,
            Map<String, Answer> answers,
            int inputTokens,
            int outputTokens,
            String requestSha256,
            String rawResponseBody
    ) {
        public JevResponse {
            answers = answers == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        }

        public NoulAnswer noulAnswer(String key) {
            Answer answer = answers.get(key);
            if (answer instanceof NoulAnswer noul) {
                return noul;
            }
            throw new IllegalStateException("Expected NoulAnswer for key '" + key + "', got: " + answer);
        }

        public double noulProbability(String key) {
            return noulAnswer(key).noul();
        }

        public ChoiceAnswer choiceAnswer(String key) {
            Answer answer = answers.get(key);
            if (answer instanceof ChoiceAnswer choice) {
                return choice;
            }
            throw new IllegalStateException("Expected ChoiceAnswer for key '" + key + "', got: " + answer);
        }

        public String choice(String key) {
            return choiceAnswer(key).choice();
        }
    }
}
