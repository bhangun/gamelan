package tech.kayys.silat.execution;

import java.util.Map;

/**
 * 🔒 Structured error information.
 * Mandatory for BPMN boundary events, ESB redelivery, AI retry strategies.
 */
public interface ExecutionError {

    enum Category {
        SYSTEM, // Infrastructure failures
        BUSINESS, // Domain rule violations
        TIMEOUT, // Execution timeouts
        EXTERNAL, // External service failures
        VALIDATION // Input/output validation failures
    }

    String getCode(); // Machine-readable error code

    Category getCategory(); // Error category

    String getMessage(); // Human-readable message

    boolean isRetriable(); // Can this error be retried?

    String getCompensationHint(); // Hint for compensation logic

    Map<String, Object> getDetails(); // Additional error context
}
