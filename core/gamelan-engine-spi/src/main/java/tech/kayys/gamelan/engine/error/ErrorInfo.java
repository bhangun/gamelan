package tech.kayys.gamelan.engine.error;

import java.util.Map;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Error Information
 */
public record ErrorInfo(
        String code,
        String message,
        String stackTrace,
        Map<String, Object> context) {
    public ErrorInfo {
        context = ExecutionPayloads.immutableMap(context);
    }

    public static ErrorInfo of(Throwable throwable) {
        return new ErrorInfo(
                throwable.getClass().getSimpleName(),
                throwable.getMessage(),
                getStackTraceAsString(throwable),
                Map.of());
    }

    private static String getStackTraceAsString(Throwable throwable) {
        var sw = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
