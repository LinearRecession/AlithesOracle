package io.alithes.oracle.web;

import io.alithes.oracle.service.QuestionService;
import io.alithes.oracle.web.dto.QueryRequest;
import io.alithes.oracle.web.dto.QueryResponse;
import jakarta.validation.Valid;
import java.util.Objects;
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

        return ProblemDetailsFactory.questionRejected(result.question().id(), result.rejectionReasons());
    }
}
