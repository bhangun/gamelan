package tech.kayys.gamelan.registry;

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
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("endpoint", endpoint);
        details.put("errorType", cause.getClass().getName());
        details.put("errorMessage", cause.getMessage());
        return new ExecutorError(
            null, executorId, Category.CONNECTION, "EXECUTOR_CONNECTION_FAILED",
            "Failed to connect to executor at " + endpoint + ": " + cause.getMessage(),
            null, details,
            true, "Check network connectivity and executor status"
        );
    }

    public static ExecutorError timeoutError(String executorId, Duration timeout, String operation) {
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("timeoutMs", timeout.toMillis());
        details.put("operation", operation);
        return new ExecutorError(
            null, executorId, Category.TIMEOUT, "EXECUTOR_TIMEOUT",
            "Executor timed out after " + timeout + " during " + operation,
            null, details,
            true, "Increase timeout or optimize executor performance"
        );
    }

    public static ExecutorError resourceError(String executorId, String resource, String constraint) {
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("resource", resource);
        details.put("constraint", constraint);
        return new ExecutorError(
            null, executorId, Category.RESOURCE, "EXECUTOR_RESOURCE_LIMIT",
            "Executor resource limit exceeded: " + resource + " (" + constraint + ")",
            null, details,
            true, "Scale executor resources or reduce load"
        );
    }

    public static ExecutorError configurationError(String executorId, String configKey, String issue) {
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("configKey", configKey);
        details.put("issue", issue);
        return new ExecutorError(
            null, executorId, Category.CONFIGURATION, "EXECUTOR_CONFIG_ERROR",
            "Executor configuration error: " + configKey + " - " + issue,
            null, details,
            false, "Fix executor configuration and restart"
        );
    }

    public static ExecutorError validationError(String executorId, String validationRule, String details) {
        java.util.Map<String, Object> dm = new java.util.HashMap<>();
        dm.put("validationRule", validationRule);
        dm.put("details", details);
        return new ExecutorError(
            null, executorId, Category.VALIDATION, "EXECUTOR_VALIDATION_FAILED",
            "Executor validation failed: " + validationRule,
            null, dm,
            false, "Fix input data or adjust validation rules"
        );
    }

    public static ExecutorError internalError(String executorId, String component, Throwable cause) {
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("component", component);
        details.put("errorType", cause.getClass().getName());
        details.put("stackTrace", getStackTrace(cause));
        return new ExecutorError(
            null, executorId, Category.INTERNAL, "EXECUTOR_INTERNAL_ERROR",
            "Executor internal error in " + component + ": " + cause.getMessage(),
            null, details,
            cause.getMessage() != null && (cause.getMessage().contains("temporary") || cause.getMessage().contains("retry")),
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
