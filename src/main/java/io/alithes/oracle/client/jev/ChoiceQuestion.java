package io.alithes.oracle.client.jev;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Discrete multi-option classification specification ({@code type = "choice"}) for Jev.
 */
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
