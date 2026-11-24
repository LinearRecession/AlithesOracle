package io.alithes.oracle.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Resolution(
        UUID questionId,
        QuestionStatus outcome,
        double confidence,
        double confirmSum,
        double denySum,
        int independentClusters,
        List<UUID> supportingEvidenceIds,
        int policyVersion,
        String model,
        String details,
        Instant resolvedAt
) {
    public Resolution {
        if (!Double.isFinite(confidence) || !Double.isFinite(confirmSum) || !Double.isFinite(denySum)) {
            throw new IllegalArgumentException("Resolution numeric fields must be finite");
        }
        supportingEvidenceIds = supportingEvidenceIds == null
                ? List.of()
                : List.copyOf(supportingEvidenceIds);
    }
}
