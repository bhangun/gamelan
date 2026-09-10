package tech.kayys.gamelan.engine.workflow;

import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Workflow Definition Identifier
 */
public record WorkflowDefinitionId(String value) {
    public WorkflowDefinitionId {
        Objects.requireNonNull(value, "WorkflowDefinitionId value cannot be null");
        if (value.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "WorkflowDefinitionId cannot be blank");
        }
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String value() {
        return value;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static WorkflowDefinitionId of(String value) {
        return new WorkflowDefinitionId(value);
    }

    public static WorkflowDefinitionId generate() {
        return new WorkflowDefinitionId(java.util.UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
