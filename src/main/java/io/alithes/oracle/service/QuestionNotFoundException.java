package io.alithes.oracle.service;

import java.util.UUID;

/**
 * Thrown when a requested question does not exist.
 */
public class QuestionNotFoundException extends RuntimeException {

    private final UUID questionId;

    public QuestionNotFoundException(UUID questionId) {
        super("Question not found: " + questionId);
        this.questionId = questionId;
    }

    public UUID questionId() {
        return questionId;
    }
}
