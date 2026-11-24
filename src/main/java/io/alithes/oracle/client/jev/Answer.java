package io.alithes.oracle.client.jev;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Discriminated union of answers returned by the Jev {@code /v1/systemone} API.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NoulAnswer.class, name = "noul"),
        @JsonSubTypes.Type(value = ChoiceAnswer.class, name = "choice")
})
public sealed interface Answer permits NoulAnswer, ChoiceAnswer {
}
