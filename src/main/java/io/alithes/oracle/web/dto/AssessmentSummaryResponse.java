package io.alithes.oracle.web.dto;

import io.alithes.oracle.domain.Assessment;
import java.time.Instant;
import java.util.UUID;

/**
 * Response representation of a Jev evidence stance assessment.
 */
public record AssessmentSummaryResponse(
        UUID id,
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
        Instant createdAt
) {
    public static AssessmentSummaryResponse from(Assessment a) {
        if (a == null) {
            return null;
        }
        return new AssessmentSummaryResponse(
                a.id(),
                a.model(),
                a.pRelevant(),
                a.pStanceFits(),
                a.pConfirms(),
                a.pPlanned(),
                a.pSpeculation(),
                a.pDenies(),
                a.stanceUnstable(),
                a.stanceConfidence(),
                a.pFresh(),
                a.attribution(),
                a.injectionFlag(),
                a.inputTokens(),
                a.createdAt()
        );
    }
}
