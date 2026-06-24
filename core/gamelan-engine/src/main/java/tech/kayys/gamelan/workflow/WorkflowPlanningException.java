package tech.kayys.gamelan.workflow;

import java.util.ArrayList;
import java.util.List;

import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Controlled planning failure for malformed workflow definitions.
 */
final class WorkflowPlanningException extends RuntimeException {

    private final String definitionId;
    private final String tenantId;
    private final List<String> validationErrors;

    private WorkflowPlanningException(
            String message,
            WorkflowDefinition definition,
            List<String> validationErrors,
            Throwable cause) {
        super(message, cause);
        this.definitionId = definition.id().value();
        this.tenantId = definition.tenantId().value();
        this.validationErrors = List.copyOf(validationErrors);
    }

    static WorkflowPlanningException invalidDefinition(
            WorkflowDefinition definition,
            ValidationResult validation) {
        List<String> errors = validationErrors(validation);
        return new WorkflowPlanningException(
                "Workflow definition is invalid and cannot be planned: " + String.join("; ", errors),
                definition,
                errors,
                null);
    }

    static WorkflowPlanningException validationFailed(
            WorkflowDefinition definition,
            RuntimeException cause) {
        String message = cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        List<String> errors = List.of("Workflow definition validation failed: " + message);
        return new WorkflowPlanningException(
                "Workflow definition validation failed before planning: " + message,
                definition,
                errors,
                cause);
    }

    String definitionId() {
        return definitionId;
    }

    String tenantId() {
        return tenantId;
    }

    List<String> validationErrors() {
        return validationErrors;
    }

    private static List<String> validationErrors(ValidationResult validation) {
        if (validation == null) {
            return List.of("Workflow definition validation failed");
        }
        List<String> errors = new ArrayList<>(validation.errors());
        if (!errors.isEmpty()) {
            return errors;
        }
        String message = validation.message();
        if (message != null && !message.isBlank()) {
            return List.of(message);
        }
        return List.of("Workflow definition validation failed");
    }
}
