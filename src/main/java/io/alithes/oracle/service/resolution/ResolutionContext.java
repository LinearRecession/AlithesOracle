package io.alithes.oracle.service.resolution;

import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.config.ScoringProperties;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import java.time.Instant;
import java.util.List;

/**
 * Immutable evaluation context passed through the ordered {@link ResolutionRule} policy chain.
 */
public record ResolutionContext(
        Question question,
        ClusterSummary summary,
        List<ScanLog> scanLogs,
        PolicyProperties policy,
        ScoringProperties scoring,
        String model,
        Instant now
) {
    public Resolution buildResolution(QuestionStatus outcome, double confidence, String details) {
        return new Resolution(
                question.id(),
                outcome,
                confidence,
                summary.confirmSum(),
                summary.denySum(),
                summary.independentClusters(),
                summary.supportingIds(),
                policy.version(),
                model,
                details,
                now
        );
    }
}
