package tech.kayys.silat.model;

import java.util.Map;

/**
 * Error Information
 */
public record ErrorInfo(
        String code,
        String message,
        String stackTrace,
        Map<String, Object> context) {
    public ErrorInfo {
        context = context != null ? Map.copyOf(context) : Map.of();
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
