package tech.kayys.gamelan.engine.error;

/**
 * Base exception that carries a standardized {@link ErrorCode}.
 */
public class GamelanException extends RuntimeException {

    private final ErrorCode errorCode;

    public GamelanException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode != null ? errorCode : ErrorCode.INTERNAL_ERROR;
    }

    public GamelanException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode != null ? errorCode : ErrorCode.INTERNAL_ERROR;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getHttpStatusCode() {
        return errorCode.getHttpStatus();
    }

    public boolean isRetryable() {
        return errorCode.isRetryable();
    }

    public String getSafeMessage() {
        String message = getMessage();
        return (message == null || message.isBlank())
                ? errorCode.getDefaultMessage()
                : message;
    }
}
