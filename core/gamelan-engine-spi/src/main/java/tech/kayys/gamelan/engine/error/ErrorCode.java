package tech.kayys.gamelan.engine.error;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Central registry for workflow-gamelan error codes.
 *
 * <p>Pattern: CATEGORY_NNN (example: WORKFLOW_001)</p>
 */
public enum ErrorCode {

    // ===== Workflow Errors =====
    WORKFLOW_NOT_FOUND(ErrorCategory.WORKFLOW, 404, "WORKFLOW_001", "Workflow not found", false),
    WORKFLOW_INVALID_DEFINITION(ErrorCategory.WORKFLOW, 400, "WORKFLOW_002", "Invalid workflow definition", false),
    WORKFLOW_VERSION_NOT_FOUND(ErrorCategory.WORKFLOW, 404, "WORKFLOW_003", "Workflow version not found", false),
    WORKFLOW_ALREADY_EXISTS(ErrorCategory.WORKFLOW, 409, "WORKFLOW_004", "Workflow already exists", false),
    WORKFLOW_STATE_INVALID(ErrorCategory.WORKFLOW, 409, "WORKFLOW_005", "Invalid workflow state", false),

    // ===== Run Errors =====
    RUN_NOT_FOUND(ErrorCategory.RUN, 404, "RUN_001", "Workflow run not found", false),
    RUN_INVALID_STATE(ErrorCategory.RUN, 409, "RUN_002", "Invalid workflow run state", false),
    RUN_ALREADY_TERMINAL(ErrorCategory.RUN, 409, "RUN_003", "Workflow run already terminal", false),
    RUN_COMPENSATION_NOT_READY(ErrorCategory.RUN, 409, "RUN_004", "Compensation state is not initialized", false),

    // ===== Task/Node Errors =====
    TASK_NOT_FOUND(ErrorCategory.TASK, 404, "TASK_001", "Task execution not found", false),
    TASK_DISPATCH_FAILED(ErrorCategory.TASK, 502, "TASK_002", "Task dispatch failed", true),
    TASK_VALIDATION_FAILED(ErrorCategory.TASK, 400, "TASK_003", "Task validation failed", false),
    TASK_EXECUTOR_UNAVAILABLE(ErrorCategory.TASK, 503, "TASK_004", "Executor unavailable", true),

    // ===== Dispatcher/Transport Errors =====
    DISPATCHER_NOT_FOUND(ErrorCategory.DISPATCH, 400, "DISPATCH_001", "No suitable dispatcher found", false),
    DISPATCHER_INVALID_REQUEST(ErrorCategory.DISPATCH, 400, "DISPATCH_002", "Dispatch request invalid", false),
    DISPATCHER_TIMEOUT(ErrorCategory.DISPATCH, 504, "DISPATCH_003", "Dispatch timeout", true),
    DISPATCHER_BAD_RESPONSE(ErrorCategory.DISPATCH, 502, "DISPATCH_004", "Dispatch response invalid", true),

    // ===== Scheduler Errors =====
    SCHEDULER_NO_EXECUTOR(ErrorCategory.SCHEDULER, 503, "SCHEDULER_001", "No executor available", true),
    SCHEDULER_CONCURRENCY_LIMIT(ErrorCategory.SCHEDULER, 429, "SCHEDULER_002", "Concurrency limit exceeded", true),

    // ===== Storage/Repository Errors =====
    STORAGE_READ_FAILED(ErrorCategory.STORAGE, 500, "STORAGE_001", "Failed to read from storage", true),
    STORAGE_WRITE_FAILED(ErrorCategory.STORAGE, 500, "STORAGE_002", "Failed to write to storage", true),
    STORAGE_SERIALIZATION_FAILED(ErrorCategory.STORAGE, 500, "STORAGE_003", "Failed to serialize/deserialize data", true),

    // ===== Plugin Errors =====
    PLUGIN_NOT_FOUND(ErrorCategory.PLUGIN, 404, "PLUGIN_001", "Plugin not found", false),
    PLUGIN_INITIALIZATION_FAILED(ErrorCategory.PLUGIN, 500, "PLUGIN_002", "Plugin initialization failed", false),
    PLUGIN_START_FAILED(ErrorCategory.PLUGIN, 500, "PLUGIN_003", "Plugin start failed", true),
    PLUGIN_STOP_FAILED(ErrorCategory.PLUGIN, 500, "PLUGIN_004", "Plugin stop failed", true),

    // ===== Security/Auth Errors =====
    TENANT_NOT_FOUND(ErrorCategory.SECURITY, 401, "SECURITY_001", "Tenant not found", false),
    TENANT_UNAUTHORIZED(ErrorCategory.SECURITY, 403, "SECURITY_002", "Unauthorized tenant access", false),
    TOKEN_INVALID(ErrorCategory.SECURITY, 401, "SECURITY_003", "Invalid token", false),
    SIGNATURE_INVALID(ErrorCategory.SECURITY, 403, "SECURITY_004", "Invalid signature", false),

    // ===== Validation Errors =====
    VALIDATION_FAILED(ErrorCategory.VALIDATION, 400, "VALIDATION_001", "Validation failed", false),
    MISSING_REQUIRED_FIELD(ErrorCategory.VALIDATION, 400, "VALIDATION_002", "Required field missing", false),

    // ===== Configuration Errors =====
    CONFIG_MISSING(ErrorCategory.CONFIG, 500, "CONFIG_001", "Required configuration missing", false),
    CONFIG_INVALID(ErrorCategory.CONFIG, 500, "CONFIG_002", "Invalid configuration value", false),

    // ===== Concurrency/Locking Errors =====
    LOCK_ACQUIRE_TIMEOUT(ErrorCategory.CONCURRENCY, 504, "CONCURRENCY_001", "Failed to acquire lock", true),
    CONCURRENCY_CONFLICT(ErrorCategory.CONCURRENCY, 409, "CONCURRENCY_002", "Concurrency conflict", true),

    // ===== Runtime/Internal Errors =====
    RUNTIME_ERROR(ErrorCategory.RUNTIME, 500, "RUNTIME_001", "Runtime execution failed", true),
    INTERNAL_ERROR(ErrorCategory.INTERNAL, 500, "INTERNAL_001", "Internal server error", true);

    public enum ErrorCategory {
        WORKFLOW("WORKFLOW"),
        RUN("RUN"),
        TASK("TASK"),
        DISPATCH("DISPATCH"),
        SCHEDULER("SCHEDULER"),
        STORAGE("STORAGE"),
        PLUGIN("PLUGIN"),
        SECURITY("SECURITY"),
        VALIDATION("VALIDATION"),
        CONFIG("CONFIG"),
        CONCURRENCY("CONCURRENCY"),
        RUNTIME("RUNTIME"),
        INTERNAL("INTERNAL");

        private final String prefix;

        ErrorCategory(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }

    private static final Map<String, ErrorCode> BY_CODE;
    private static final Map<ErrorCategory, Map<String, ErrorCode>> BY_CATEGORY;

    static {
        Map<String, ErrorCode> byCode = new HashMap<>();
        Map<ErrorCategory, Map<String, ErrorCode>> byCategory = new EnumMap<>(ErrorCategory.class);

        for (ErrorCategory category : ErrorCategory.values()) {
            byCategory.put(category, new HashMap<>());
        }

        for (ErrorCode errorCode : values()) {
            if (!errorCode.code.startsWith(errorCode.category.getPrefix() + "_")) {
                throw new IllegalStateException(
                        "ErrorCode prefix mismatch: " + errorCode.name() + " -> " + errorCode.code);
            }
            ErrorCode previous = byCode.put(errorCode.code, errorCode);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate error code: " + errorCode.code + " (" + errorCode.name() + ", " + previous.name() + ")"
                );
            }
            byCategory.get(errorCode.category).put(errorCode.code, errorCode);
        }

        BY_CODE = Collections.unmodifiableMap(byCode);
        Map<ErrorCategory, Map<String, ErrorCode>> immutableByCategory = new EnumMap<>(ErrorCategory.class);
        for (Map.Entry<ErrorCategory, Map<String, ErrorCode>> entry : byCategory.entrySet()) {
            immutableByCategory.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        BY_CATEGORY = Collections.unmodifiableMap(immutableByCategory);
    }

    private final ErrorCategory category;
    private final int httpStatus;
    private final String code;
    private final String defaultMessage;
    private final boolean retryable;

    ErrorCode(ErrorCategory category, int httpStatus, String code, String defaultMessage, boolean retryable) {
        this.category = Objects.requireNonNull(category, "category");
        this.httpStatus = httpStatus;
        this.code = Objects.requireNonNull(code, "code");
        this.defaultMessage = Objects.requireNonNull(defaultMessage, "defaultMessage");
        this.retryable = retryable;
    }

    public static ErrorCode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return INTERNAL_ERROR;
        }
        ErrorCode errorCode = BY_CODE.get(code.trim());
        return errorCode != null ? errorCode : INTERNAL_ERROR;
    }

    public static Map<String, ErrorCode> byCategory(ErrorCategory category) {
        Map<String, ErrorCode> codes = BY_CATEGORY.get(category);
        return codes == null ? Collections.emptyMap() : codes;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
