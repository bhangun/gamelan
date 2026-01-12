package tech.kayys.silat.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Registry error information
 */
public record ExecutorError(
    String errorId,
    String executorId,
    Category category,
    String code,
    String message,
    Instant occurredAt,
    Map<String, Object> details,
    boolean retriable,
    String recoveryHint
) {
    public enum Category {
        CONNECTION, TIMEOUT, RESOURCE, CONFIGURATION, VALIDATION, INTERNAL, UNKNOWN
    }

    @JsonCreator
    public ExecutorError(
            @JsonProperty("errorId") String errorId,
            @JsonProperty("executorId") String executorId,
            @JsonProperty("category") Category category,
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("details") Map<String, Object> details,
            @JsonProperty("retriable") boolean retriable,
            @JsonProperty("recoveryHint") String recoveryHint) {

        this.errorId = errorId != null ? errorId : java.util.UUID.randomUUID().toString();
        this.executorId = executorId;
        this.category = category;
        this.code = code;
        this.message = message;
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
        this.details = details != null ? Map.copyOf(details) : Map.of();
        this.retriable = retriable;
        this.recoveryHint = recoveryHint;
    }

    // Factory methods using constructor instead of builder
    public static ExecutorError connectionError(String executorId, String endpoint, Throwable cause) {
        return new ExecutorError(
            null, executorId, Category.CONNECTION, "EXECUTOR_CONNECTION_FAILED",
            "Failed to connect to executor at " + endpoint + ": " + cause.getMessage(),
            null, Map.of("endpoint", endpoint, "errorType", cause.getClass().getName(), "errorMessage", cause.getMessage()),
            true, "Check network connectivity and executor status"
        );
    }

    public static ExecutorError timeoutError(String executorId, Duration timeout, String operation) {
        return new ExecutorError(
            null, executorId, Category.TIMEOUT, "EXECUTOR_TIMEOUT",
            "Executor timed out after " + timeout + " during " + operation,
            null, Map.of("timeoutMs", timeout.toMillis(), "operation", operation),
            true, "Increase timeout or optimize executor performance"
        );
    }

    public static ExecutorError resourceError(String executorId, String resource, String constraint) {
        return new ExecutorError(
            null, executorId, Category.RESOURCE, "EXECUTOR_RESOURCE_LIMIT",
            "Executor resource limit exceeded: " + resource + " (" + constraint + ")",
            null, Map.of("resource", resource, "constraint", constraint),
            true, "Scale executor resources or reduce load"
        );
    }

    public static ExecutorError configurationError(String executorId, String configKey, String issue) {
        return new ExecutorError(
            null, executorId, Category.CONFIGURATION, "EXECUTOR_CONFIG_ERROR",
            "Executor configuration error: " + configKey + " - " + issue,
            null, Map.of("configKey", configKey, "issue", issue),
            false, "Fix executor configuration and restart"
        );
    }

    public static ExecutorError validationError(String executorId, String validationRule, String details) {
        return new ExecutorError(
            null, executorId, Category.VALIDATION, "EXECUTOR_VALIDATION_FAILED",
            "Executor validation failed: " + validationRule,
            null, Map.of("validationRule", validationRule, "details", details),
            false, "Fix input data or adjust validation rules"
        );
    }

    public static ExecutorError internalError(String executorId, String component, Throwable cause) {
        return new ExecutorError(
            null, executorId, Category.INTERNAL, "EXECUTOR_INTERNAL_ERROR",
            "Executor internal error in " + component + ": " + cause.getMessage(),
            null, Map.of("component", component, "errorType", cause.getClass().getName(), "stackTrace", getStackTrace(cause)),
            cause.getMessage().contains("temporary") || cause.getMessage().contains("retry"),
            "Check executor logs and restart if necessary"
        );
    }

    private static String getStackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    public boolean isConnectionError() {
        return category == Category.CONNECTION;
    }

    public boolean isTimeoutError() {
        return category == Category.TIMEOUT;
    }

    public boolean isRecoverable() {
        return retriable ||
                category == Category.CONNECTION ||
                category == Category.TIMEOUT ||
                category == Category.RESOURCE;
    }
}
