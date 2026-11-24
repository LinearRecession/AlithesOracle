package io.alithes.oracle.web;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.service.QuestionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST request and response DTO records for {@link QuestionController}.
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateQuestionRequest(
            @NotBlank(message = "text must not be blank")
            String text,
            @Size(max = 2000, message = "resolutionCriteria must be at most 2000 characters")
            String resolutionCriteria,
            @NotNull(message = "windowStart must not be null")
            Instant windowStart,
            @NotNull(message = "deadline must not be null")
            Instant deadline,
            @Min(value = 0, message = "graceHours must be >= 0")
            @Max(value = 720, message = "graceHours must be <= 720")
            Integer graceHours,
            @Size(max = 10, message = "searchQueries must contain at most 10 entries")
            List<String> searchQueries,
            @Size(max = 2048, message = "webhookUrl must be at most 2048 characters")
            String webhookUrl
    ) {
    }

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

    public record ManualReviewRequest(
            @NotNull(message = "outcome must not be null")
            QuestionStatus outcome,
            @Size(max = 2000, message = "reason must be at most 2000 characters")
            String reason
    ) {
    }

    public record QueryRequest(
            @NotBlank(message = "query must not be blank")
            String query,
            @Size(max = 2000, message = "resolutionCriteria must be at most 2000 characters")
            String resolutionCriteria,
            @Min(value = 1, message = "lookbackDays must be >= 1")
            @Max(value = 79, message = "lookbackDays must be <= 79")
            Integer lookbackDays,
            Instant windowStart,
            Instant deadline,
            @Min(value = 0, message = "graceHours must be >= 0")
            @Max(value = 720, message = "graceHours must be <= 720")
            Integer graceHours,
            @Size(max = 10, message = "searchQueries must contain at most 10 entries")
            List<String> searchQueries
    ) {
    }

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

    public record SystemStatusResponse(
            String jevModel,
            boolean jevApiKeyConfigured,
            String jevBaseUrl,
            String gdeltBaseUrl,
            boolean schedulerEnabled,
            long schedulerFixedDelayMs,
            int policyVersion,
            OracleProperties.Policy policy,
            Map<QuestionStatus, Long> questionCounts
    ) {
    }
}
