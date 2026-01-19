package tech.kayys.gamelan.plugin.validator;

import java.util.List;

import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Plugin interface for workflow validators
 * 
 * Validator plugins can add custom validation rules for workflow definitions.
 */
public interface WorkflowValidatorPlugin extends GamelanPlugin {

    /**
     * Validate a workflow definition
     * 
     * @param definition the workflow definition to validate
     * @return list of validation errors (empty if valid)
     */
    List<ValidationError> validate(WorkflowDefinition definition);

    /**
     * Get the validation rules provided by this plugin
     * 
     * @return list of validation rule descriptions
     */
    List<String> getValidationRules();

    /**
     * Workflow definition information
     */
    interface WorkflowDefinition {
        String definitionId();

        String name();

        String version();

        List<NodeDefinition> nodes();

        List<Transition> transitions();
    }

    /**
     * Node definition information
     */
    interface NodeDefinition {
        String nodeId();

        String nodeType();

        java.util.Map<String, Object> configuration();
    }

    /**
     * Transition information
     */
    interface Transition {
        String fromNodeId();

        String toNodeId();

        String condition();
    }

    /**
     * Validation error
     */
    record ValidationError(
            String rule,
            String message,
            String location,
            Severity severity) {
        public enum Severity {
            ERROR, WARNING, INFO
        }
    }
}
