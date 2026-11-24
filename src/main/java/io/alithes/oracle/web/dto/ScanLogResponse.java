package io.alithes.oracle.web.dto;

import io.alithes.oracle.domain.ScanLog;
import java.time.Instant;
import java.util.UUID;

/**
 * Response representation of an execution entry in {@code scan_logs}.
 */
public record ScanLogResponse(
        UUID id,
        UUID questionId,
        Instant startedAt,
        Instant finishedAt,
        boolean gdeltOk,
        int articlesFound,
        int newEvidence,
        String error
) {
    public static ScanLogResponse from(ScanLog s) {
        return new ScanLogResponse(
                s.id(),
                s.questionId(),
                s.startedAt(),
                s.finishedAt(),
                s.gdeltOk(),
                s.articlesFound(),
                s.newEvidence(),
                s.error()
        );
    }
}
