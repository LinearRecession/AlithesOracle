package io.alithes.oracle.client;

/**
 * Non-retryable exception thrown when GDELT rejects a query with an HTTP 200 plain-text error.
 */
public class GdeltQueryException extends GdeltException {

    public GdeltQueryException(String message) {
        super(message, 200, message);
    }

    public GdeltQueryException(String message, String responseBody) {
        super(message, 200, responseBody);
    }
}
