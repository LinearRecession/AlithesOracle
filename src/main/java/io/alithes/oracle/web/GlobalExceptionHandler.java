package io.alithes.oracle.web;

import io.alithes.oracle.service.ConflictException;
import io.alithes.oracle.service.QuestionNotFoundException;
import io.alithes.oracle.service.ResolutionNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates application and request validation errors into RFC 7807 {@link ProblemDetail} responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(QuestionNotFoundException.class)
    public ProblemDetail handleQuestionNotFound(QuestionNotFoundException ex) {
        return ProblemDetailsFactory.notFound("Question Not Found", ex.getMessage(), ex.questionId());
    }

    @ExceptionHandler(ResolutionNotFoundException.class)
    public ProblemDetail handleResolutionNotFound(ResolutionNotFoundException ex) {
        return ProblemDetailsFactory.notFound("Resolution Not Found", ex.getMessage(), ex.questionId());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        return ProblemDetailsFactory.notFound("Not Found", ex.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetailsFactory.conflict(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetailsFactory.badRequest(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .toList();
        return ProblemDetailsFactory.validationFailed(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException ex) {
        return ProblemDetailsFactory.badRequest("Malformed or invalid request payload");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ProblemDetailsFactory.badRequest("Invalid value for parameter '" + ex.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
        return ProblemDetailsFactory.badRequest(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception in REST API", ex);
        return ProblemDetailsFactory.internalError();
    }

    private static String formatFieldError(FieldError error) {
        String message = error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage();
        return error.getField() + ": " + message;
    }
}
