package io.alithes.oracle.domain;

import java.time.Instant;
import java.util.UUID;

public record Assessment(
        UUID id,
        UUID evidenceId,
        UUID questionId,
        String model,
        double pRelevant,
        double pStanceFits,
        double pConfirms,
        double pPlanned,
        double pSpeculation,
        double pDenies,
        boolean stanceUnstable,
        double stanceConfidence,
        double pFresh,
        String attribution,
        boolean injectionFlag,
        int inputTokens,
        String requestSha256,
        String responseJson,
        Instant createdAt
) {
    public Assessment {
        requireFinite(pRelevant, "pRelevant");
        requireFinite(pStanceFits, "pStanceFits");
        requireFinite(pConfirms, "pConfirms");
        requireFinite(pPlanned, "pPlanned");
        requireFinite(pSpeculation, "pSpeculation");
        requireFinite(pDenies, "pDenies");
        requireFinite(stanceConfidence, "stanceConfidence");
        requireFinite(pFresh, "pFresh");
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Assessment." + field + " must be finite, got " + value);
        }
    }
}
