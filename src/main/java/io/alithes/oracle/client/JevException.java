package io.alithes.oracle.client;

/**
 * Exception thrown when a request to the TypeSafe AI Jev API fails.
 */
public class JevException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public JevException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public JevException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public JevException(String message, Throwable cause) {
        this(message, -1, null, cause);
    }

    public int statusCode() {
        return statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
