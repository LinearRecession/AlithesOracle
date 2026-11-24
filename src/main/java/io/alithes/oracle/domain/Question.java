package io.alithes.oracle.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Question(
        UUID id,
        String text,
        String resolutionCriteria,
        Instant windowStart,
        Instant deadline,
        int graceHours,
        QuestionStatus status,
        String statusReason,
        List<String> searchQueries,
        String webhookUrl,
        Instant lastScanEnd,
        Instant createdAt,
        Instant updatedAt
) {
    public Question {
        if (graceHours < 0) {
            throw new IllegalArgumentException("graceHours must be >= 0, got " + graceHours);
        }
        searchQueries = searchQueries == null ? List.of() : List.copyOf(searchQueries);
    }

    public Instant finalAt() {
        return deadline.plus(Duration.ofHours(graceHours));
    }

    public Question withStatus(QuestionStatus newStatus, String newStatusReason, Instant now) {
        return new Question(
                id,
                text,
                resolutionCriteria,
                windowStart,
                deadline,
                graceHours,
                newStatus,
                newStatusReason,
                searchQueries,
                webhookUrl,
                lastScanEnd,
                createdAt,
                now
        );
    }

    public Question withLastScanEnd(Instant newLastScanEnd, Instant now) {
        return new Question(
                id,
                text,
                resolutionCriteria,
                windowStart,
                deadline,
                graceHours,
                status,
                statusReason,
                searchQueries,
                webhookUrl,
                newLastScanEnd,
                createdAt,
                now
        );
    }

    public Question withSearchQueries(List<String> newSearchQueries, Instant now) {
        return new Question(
                id,
                text,
                resolutionCriteria,
                windowStart,
                deadline,
                graceHours,
                status,
                statusReason,
                newSearchQueries,
                webhookUrl,
                lastScanEnd,
                createdAt,
                now
        );
    }
}
