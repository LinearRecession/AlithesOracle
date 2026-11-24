package io.alithes.oracle.web;

import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.service.QuestionService;
import io.alithes.oracle.web.ApiDtos.CreateQuestionRequest;
import io.alithes.oracle.web.ApiDtos.EvidenceDetailResponse;
import io.alithes.oracle.web.ApiDtos.ManualReviewRequest;
import io.alithes.oracle.web.ApiDtos.QuestionResponse;
import io.alithes.oracle.web.ApiDtos.ScanLogResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the v1 AlithesOracle question lifecycle and evidence endpoints.
 */
@RestController
@RequestMapping("/v1/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = Objects.requireNonNull(questionService, "questionService must not be null");
    }

    @PostMapping
    public ResponseEntity<?> createQuestion(@Valid @RequestBody CreateQuestionRequest request) {
        QuestionService.CreateQuestionResult result = questionService.createQuestion(
                request.text(),
                request.resolutionCriteria(),
                request.windowStart(),
                request.deadline(),
                request.graceHours(),
                request.searchQueries(),
                request.webhookUrl()
        );

        if (result.accepted()) {
            Question created = result.question();
            URI location = URI.create("/v1/questions/" + created.id());
            return ResponseEntity.created(location).body(QuestionResponse.from(created));
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                String.join("; ", result.reasons())
        );
        problem.setTitle("Question Rejected");
        problem.setProperty("questionId", result.question().id());
        problem.setProperty("reasons", result.reasons());
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @GetMapping
    public List<QuestionResponse> listQuestions(
            @RequestParam(name = "status", required = false) QuestionStatus status
    ) {
        return questionService.listQuestions(status).stream()
                .map(QuestionResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public QuestionResponse getQuestion(@PathVariable UUID id) {
        return QuestionResponse.from(questionService.getQuestion(id));
    }

    @GetMapping("/{id}/scans")
    public List<ScanLogResponse> getScanLogs(@PathVariable UUID id) {
        return questionService.getScanLogs(id).stream()
                .map(ScanLogResponse::from)
                .toList();
    }

    @GetMapping("/{id}/evidence")
    public List<EvidenceDetailResponse> getEvidence(
            @PathVariable UUID id,
            @RequestParam(name = "stage", required = false) EvidenceStage stage
    ) {
        return questionService.getEvidence(id, stage).stream()
                .map(EvidenceDetailResponse::from)
                .toList();
    }

    @GetMapping("/{id}/resolution")
    public Resolution getResolution(@PathVariable UUID id) {
        return questionService.getResolution(id);
    }

    @PostMapping("/{id}/rescan")
    public QuestionResponse rescan(@PathVariable UUID id) {
        return QuestionResponse.from(questionService.rescan(id));
    }

    @PostMapping("/{id}/review")
    public Resolution review(
            @PathVariable UUID id,
            @Valid @RequestBody ManualReviewRequest request
    ) {
        return questionService.manualReview(id, request.outcome(), request.reason());
    }
}
