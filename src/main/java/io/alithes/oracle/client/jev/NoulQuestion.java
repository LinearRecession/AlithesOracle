package io.alithes.oracle.client.jev;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boolean probability question specification ({@code type = "noul"}) for Jev.
 */
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
