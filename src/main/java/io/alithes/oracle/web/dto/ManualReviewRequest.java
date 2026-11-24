package io.alithes.oracle.web.dto;

import io.alithes.oracle.domain.QuestionStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for settling a disputed question ({@code POST /v1/questions/{id}/review}).
 */
public record ManualReviewRequest(
        @NotNull(message = "outcome must not be null")
        QuestionStatus outcome,
        @Size(max = 2000, message = "reason must be at most 2000 characters")
        String reason
) {
}
