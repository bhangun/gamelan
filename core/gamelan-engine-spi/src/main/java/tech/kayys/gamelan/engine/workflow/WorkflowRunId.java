package tech.kayys.gamelan.engine.workflow;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.UUID;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

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
public record WorkflowRunId(@JsonValue String value) {
    public WorkflowRunId {
        Objects.requireNonNull(value, "WorkflowRunId cannot be null");
        if (value.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "WorkflowRunId cannot be blank");
        }
    }

    public static WorkflowRunId generate() {
        return new WorkflowRunId(UUID.randomUUID().toString());
    }

    public static WorkflowRunId of(String value) {
        return new WorkflowRunId(value);
    }
}
