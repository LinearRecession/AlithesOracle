package io.alithes.oracle.web.dto;

import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response representation of a persisted oracle question.
 */
public record QuestionResponse(
        UUID id,
        String text,
        String resolutionCriteria,
        Instant windowStart,
        Instant deadline,
        int graceHours,
        Instant finalAt,
        QuestionStatus status,
        String statusReason,
        List<String> searchQueries,
        String webhookUrl,
        Instant lastScanEnd,
        Instant createdAt,
        Instant updatedAt
) {
    public static QuestionResponse from(Question q) {
        return new QuestionResponse(
                q.id(),
                q.text(),
                q.resolutionCriteria(),
                q.windowStart(),
                q.deadline(),
                q.graceHours(),
                q.finalAt(),
                q.status(),
                q.statusReason(),
                q.searchQueries(),
                q.webhookUrl(),
                q.lastScanEnd(),
                q.createdAt(),
                q.updatedAt()
        );
    }
}
