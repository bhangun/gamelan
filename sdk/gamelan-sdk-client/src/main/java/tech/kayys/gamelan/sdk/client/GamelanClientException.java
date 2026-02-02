package tech.kayys.gamelan.sdk.client;

/**
 * Exception thrown by the Gamelan SDK client when an error occurs during
 * communication
 * with the Gamelan service or during local execution.
 */
public class GamelanClientException extends RuntimeException {
    private final int statusCode;

    /**
     * Constructs a new exception with the specified detail message.
     * 
     * @param message the detail message
     */
    public GamelanClientException(String message) {
        this(message, -1, null);
    }

    /**
     * Constructs a new exception with the specified detail message and status code.
     * 
     * @param message    the detail message
     * @param statusCode the HTTP status code or -1 if not applicable
     */
    public GamelanClientException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause   the cause of the exception
     */
    public GamelanClientException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    /**
     * Constructs a new exception with the specified detail message, status code,
     * and cause.
     * 
     * @param message    the detail message
     * @param statusCode the HTTP status code or -1 if not applicable
     * @param cause      the cause of the exception
     */
    public GamelanClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Returns the status code associated with this exception.
     * 
     * @return the status code, or -1 if not set
     */
    public int getStatusCode() {
        return statusCode;
    }
}
