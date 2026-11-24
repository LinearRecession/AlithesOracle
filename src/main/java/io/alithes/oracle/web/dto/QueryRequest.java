package io.alithes.oracle.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request payload for synchronous oracle evaluation ({@code POST /v1/query}).
 */
public record QueryRequest(
        @NotBlank(message = "query must not be blank")
        String query,
        @Size(max = 2000, message = "resolutionCriteria must be at most 2000 characters")
        String resolutionCriteria,
        @Min(value = 1, message = "lookbackDays must be >= 1")
        @Max(value = 79, message = "lookbackDays must be <= 79")
        Integer lookbackDays,
        Instant windowStart,
        Instant deadline,
        @Min(value = 0, message = "graceHours must be >= 0")
        @Max(value = 720, message = "graceHours must be <= 720")
        Integer graceHours,
        @Size(max = 10, message = "searchQueries must contain at most 10 entries")
        List<String> searchQueries
) {
}
