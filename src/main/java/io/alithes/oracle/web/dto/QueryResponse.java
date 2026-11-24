package io.alithes.oracle.web.dto;

import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.service.QuestionService;
import java.util.List;
import java.util.UUID;

/**
 * Synchronous query response wrapping outcome, confidence, resolution details, and evidence.
 */
public record QueryResponse(
        UUID questionId,
        String query,
        QuestionStatus status,
        Boolean happened,
        Double confidence,
        String summary,
        List<String> searchQueries,
        Resolution resolution,
        List<EvidenceDetailResponse> evidence
) {
    public static QueryResponse from(QuestionService.QueryExecutionResult result) {
        Question q = result.question();
        Resolution r = result.resolution();
        Boolean happened = switch (q.status()) {
            case RESOLVED_YES -> Boolean.TRUE;
            case RESOLVED_NO -> Boolean.FALSE;
            default -> null;
        };
        Double confidence = r != null ? r.confidence() : null;
        String summary = r != null ? r.details() : q.statusReason();
        List<EvidenceDetailResponse> evidenceResponses = result.evidence().stream()
                .map(EvidenceDetailResponse::from)
                .toList();
        return new QueryResponse(
                q.id(),
                q.text(),
                q.status(),
                happened,
                confidence,
                summary,
                q.searchQueries(),
                r,
                evidenceResponses
        );
    }
}
