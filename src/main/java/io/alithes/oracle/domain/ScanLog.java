package io.alithes.oracle.domain;

import java.time.Instant;
import java.util.UUID;

public record ScanLog(
        UUID id,
        UUID questionId,
        Instant startedAt,
        Instant finishedAt,
        boolean gdeltOk,
        int articlesFound,
        int newEvidence,
        String error
) {
}
