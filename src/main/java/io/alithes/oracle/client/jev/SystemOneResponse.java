package io.alithes.oracle.client.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Wire response payload returned by Jev {@code POST /v1/systemone}.
 */
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
