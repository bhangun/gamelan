package tech.kayys.silat.model;

import java.util.Objects;
import java.util.UUID;

/**
 * ============================================================================
 * DOMAIN MODEL - VALUE OBJECTS & ENTITIES
 * ============================================================================
 * Immutable domain objects following DDD principles
 */

// ==================== VALUE OBJECTS ====================

/**
 * Workflow Run Identifier - Primary aggregate identifier
 */
public record WorkflowRunId(String value) {
    public WorkflowRunId {
        Objects.requireNonNull(value, "WorkflowRunId cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("WorkflowRunId cannot be blank");
        }
    }

    public static WorkflowRunId generate() {
        return new WorkflowRunId(UUID.randomUUID().toString());
    }

    public static WorkflowRunId of(String value) {
        return new WorkflowRunId(value);
    }
}
