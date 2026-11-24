package io.alithes.oracle.web;

import io.alithes.oracle.service.QuestionService;
import io.alithes.oracle.web.ApiDtos.QueryRequest;
import io.alithes.oracle.web.ApiDtos.QueryResponse;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synchronous natural-language query endpoint for running the oracle on demand without a scheduler.
 */
@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final QuestionService questionService;

    public QueryController(QuestionService questionService) {
        this.questionService = Objects.requireNonNull(questionService, "questionService must not be null");
    }

    @PostMapping
    public ResponseEntity<?> query(@Valid @RequestBody QueryRequest request) {
        QuestionService.QueryExecutionResult result = questionService.runQuery(
                request.query(),
                request.resolutionCriteria(),
                request.lookbackDays(),
                request.windowStart(),
                request.deadline(),
                request.graceHours(),
                request.searchQueries()
        );

        if (result.accepted()) {
            return ResponseEntity.ok(QueryResponse.from(result));
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                String.join("; ", result.rejectionReasons())
        );
        problem.setTitle("Question Rejected");
        problem.setProperty("questionId", result.question().id());
        problem.setProperty("reasons", result.rejectionReasons());
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
