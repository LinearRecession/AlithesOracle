package io.alithes.oracle.service;

import java.util.UUID;

/**
 * Thrown when a question exists but does not yet have a resolution.
 */
public class ResolutionNotFoundException extends RuntimeException {

    private final UUID questionId;

    public ResolutionNotFoundException(UUID questionId) {
        super("Resolution not found for question: " + questionId);
        this.questionId = questionId;
    }

    public UUID questionId() {
        return questionId;
    }
}
