package io.alithes.oracle.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request payload for creating a tracked event question ({@code POST /v1/questions}).
 */
public record CreateQuestionRequest(
        @NotBlank(message = "text must not be blank")
        String text,
        @Size(max = 2000, message = "resolutionCriteria must be at most 2000 characters")
        String resolutionCriteria,
        @NotNull(message = "windowStart must not be null")
        Instant windowStart,
        @NotNull(message = "deadline must not be null")
        Instant deadline,
        @Min(value = 0, message = "graceHours must be >= 0")
        @Max(value = 720, message = "graceHours must be <= 720")
        Integer graceHours,
        @Size(max = 10, message = "searchQueries must contain at most 10 entries")
        List<String> searchQueries,
        @Size(max = 2048, message = "webhookUrl must be at most 2048 characters")
        String webhookUrl
) {
}
