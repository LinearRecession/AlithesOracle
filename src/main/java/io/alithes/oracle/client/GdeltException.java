package io.alithes.oracle.client;

/**
 * Exception thrown when querying the GDELT DOC 2.0 API fails.
 */
public class GdeltException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public GdeltException(String message) {
        this(message, -1, null);
    }

    public GdeltException(String message, Throwable cause) {
        this(message, -1, null, cause);
    }

    public GdeltException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public GdeltException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
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
