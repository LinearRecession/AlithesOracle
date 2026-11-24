package io.alithes.oracle.web.dto;

import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.service.QuestionService;
import java.time.Instant;
import java.util.UUID;

/**
 * Response representation pairing an {@link Evidence} item with its optional assessment summary.
 */
public record EvidenceDetailResponse(
        UUID id,
        UUID questionId,
        String url,
        String normalizedUrl,
        String domain,
        String title,
        Instant seenAt,
        Instant publishedAt,
        String language,
        String sourceCountry,
        EvidenceStage stage,
        FetchStatus fetchStatus,
        Double headlineScore,
        String contentSha256,
        String excerpt,
        long simhash,
        Instant createdAt,
        AssessmentSummaryResponse assessment
) {
    public static EvidenceDetailResponse from(QuestionService.EvidenceDetail detail) {
        Evidence ev = detail.evidence();
        return new EvidenceDetailResponse(
                ev.id(),
                ev.questionId(),
                ev.url(),
                ev.normalizedUrl(),
                ev.domain(),
                ev.title(),
                ev.seenAt(),
                ev.publishedAt(),
                ev.language(),
                ev.sourceCountry(),
                ev.stage(),
                ev.fetchStatus(),
                ev.headlineScore(),
                ev.contentSha256(),
                ev.excerpt(),
                ev.simhash(),
                ev.createdAt(),
                AssessmentSummaryResponse.from(detail.assessment())
        );
    }
}
