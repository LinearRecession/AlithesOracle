package io.alithes.oracle.client;

/**
 * Non-retryable exception thrown when Jev rejects a request with {@code max_tokens_exceeded}.
 */
public class JevContextTooLargeException extends JevException {

    public JevContextTooLargeException(String message, String responseBody) {
        super(message, 400, responseBody);
    }
}
