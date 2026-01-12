package tech.kayys.silat.model;

import java.util.Objects;

/**
 * Workflow Definition Identifier
 */
public record WorkflowDefinitionId(String value) {
    public WorkflowDefinitionId {
        Objects.requireNonNull(value, "WorkflowDefinitionId cannot be null");
    }

    public static WorkflowDefinitionId of(String value) {
        return new WorkflowDefinitionId(value);
    }
}
