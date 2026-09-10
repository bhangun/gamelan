package tech.kayys.gamelan.engine.saga;

import java.util.Map;

import tech.kayys.gamelan.engine.error.ErrorInfo;

/**
 * Canonical compensation failure codes and normalization helpers.
 */
public final class CompensationErrors {
    public static final String COMPENSATION_FAILED = "COMPENSATION_FAILED";
    public static final String DEFAULT_COMPENSATION_FAILED_MESSAGE = "Compensation failed";

    private CompensationErrors() {
    }

    public static ErrorInfo failed() {
        return failed(DEFAULT_COMPENSATION_FAILED_MESSAGE);
    }

    public static ErrorInfo failed(String message) {
        return new ErrorInfo(
                COMPENSATION_FAILED,
                safeMessage(message),
                "",
                Map.of());
    }

    public static ErrorInfo normalizeFailure(ErrorInfo error) {
        if (error == null) {
            return failed();
        }

        String code = error.code() != null && !error.code().isBlank()
                ? error.code()
                : COMPENSATION_FAILED;
        String message = safeMessage(error.message());
        String stackTrace = error.stackTrace() != null ? error.stackTrace() : "";
        return new ErrorInfo(code, message, stackTrace, error.context());
    }

    private static String safeMessage(String message) {
        return message != null && !message.isBlank()
                ? message
                : DEFAULT_COMPENSATION_FAILED_MESSAGE;
    }
}
