package io.alithes.oracle.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Factory creating consistent RFC 7807 {@link ProblemDetail} payloads across controllers
 * and exception handlers.
 */
public final class ProblemDetailsFactory {

    private ProblemDetailsFactory() {
    }

    public static ResponseEntity<ProblemDetail> questionRejected(UUID questionId, List<String> reasons) {
        List<String> safeReasons = reasons == null ? List.of() : reasons;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                String.join("; ", safeReasons)
        );
        problem.setTitle("Question Rejected");
        problem.setProperty("questionId", questionId);
        problem.setProperty("reasons", safeReasons);
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    public static ProblemDetail notFound(String title, String detail, UUID questionId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle(title);
        if (questionId != null) {
            problem.setProperty("questionId", questionId);
        }
        return problem;
    }

    public static ProblemDetail conflict(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("Conflict");
        return problem;
    }

    public static ProblemDetail badRequest(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        return problem;
    }

    public static ProblemDetail validationFailed(List<String> errors) {
        List<String> safeErrors = errors == null ? List.of() : errors;
        String detail = safeErrors.isEmpty() ? "Request validation failed" : String.join("; ", safeErrors);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Failed");
        problem.setProperty("errors", safeErrors);
        return problem;
    }

    public static ProblemDetail internalError() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred"
        );
        problem.setTitle("Internal Server Error");
        return problem;
    }
}
