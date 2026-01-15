package tech.kayys.silat.model;

import java.util.Objects;

/**
 * Workflow Definition Identifier
 */
public record WorkflowDefinitionId(String value) {
    public WorkflowDefinitionId {
        Objects.requireNonNull(value, "WorkflowDefinitionId value cannot be null");
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String value() {
        return value;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static WorkflowDefinitionId of(String value) {
        return new WorkflowDefinitionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
