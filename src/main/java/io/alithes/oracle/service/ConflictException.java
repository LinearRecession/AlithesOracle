package io.alithes.oracle.service;

/**
 * Thrown when an operation conflicts with the current state of a question.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
