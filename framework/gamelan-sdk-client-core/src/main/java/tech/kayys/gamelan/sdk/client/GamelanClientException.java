package tech.kayys.gamelan.sdk.client;

/**
 * Custom exception for Gamelan SDK client errors.
 */
public class GamelanClientException extends RuntimeException {
    private final int statusCode;

    public GamelanClientException(String message) {
        this(message, 0, null);
    }

    public GamelanClientException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public GamelanClientException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public GamelanClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * @return the HTTP status code (if applicable), or 0
     */
    public int statusCode() {
        return statusCode;
    }
}
