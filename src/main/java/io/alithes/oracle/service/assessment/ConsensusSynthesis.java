package io.alithes.oracle.service.assessment;

/**
 * Aggregated Jev consensus synthesis probabilities over top chronological evidence reports.
 */
public record ConsensusSynthesis(
        String model,
        double pConsensusYes,
        double pConsensusNo,
        double pSufficient
) {
}
