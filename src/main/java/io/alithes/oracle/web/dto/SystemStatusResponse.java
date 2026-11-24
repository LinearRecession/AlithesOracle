package io.alithes.oracle.web.dto;

import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.domain.QuestionStatus;
import java.util.Map;

/**
 * Non-sensitive runtime status and active policy snapshot exposed by {@code GET /v1/system}.
 */
public record SystemStatusResponse(
        String jevModel,
        boolean jevApiKeyConfigured,
        String jevBaseUrl,
        String gdeltBaseUrl,
        boolean schedulerEnabled,
        long schedulerFixedDelayMs,
        int policyVersion,
        PolicyProperties policy,
        Map<QuestionStatus, Long> questionCounts
) {
}
