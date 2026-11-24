package io.alithes.oracle.client.jev;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Discriminated union of question specifications sent in a Jev {@code /v1/systemone} request.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NoulQuestion.class, name = "noul"),
        @JsonSubTypes.Type(value = ChoiceQuestion.class, name = "choice")
})
public sealed interface QuestionSpec permits NoulQuestion, ChoiceQuestion {
    String instructions();
}
