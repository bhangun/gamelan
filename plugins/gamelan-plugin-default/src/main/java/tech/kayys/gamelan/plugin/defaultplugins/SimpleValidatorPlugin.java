package tech.kayys.gamelan.plugin.defaultplugins;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginException;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.plugin.validator.WorkflowValidatorPlugin;

/**
 * Default workflow validator plugin that performs basic validation checks
 */
public class SimpleValidatorPlugin implements WorkflowValidatorPlugin {

    private PluginContext context;
    private Logger logger;
    private volatile boolean started = false;

    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        this.logger = context.getLogger();
        logger.info("Simple Validator Plugin initialized");
    }

    public void start() throws PluginException {
        started = true;
        logger.info("Simple Validator Plugin started");
    }

    public void stop() throws PluginException {
        started = false;
        logger.info("Simple Validator Plugin stopped");
    }

    public PluginMetadata getMetadata() {
        return new PluginMetadata(
                "simple-validator",
                "Simple Validator Plugin",
                "1.0.0",
                "Gamelan Team",
                "Performs basic workflow validation",
                null,
                null);
    }

    @Override
    public List<ValidationError> validate(WorkflowDefinition workflowDefinition) {
        if (!started) {
            return new ArrayList<>();
        }

        List<ValidationError> errors = new ArrayList<>();

        // Validate workflow has a name
        if (workflowDefinition.name() == null || workflowDefinition.name().trim().isEmpty()) {
            errors.add(new ValidationError("WORKFLOW_NAME_RULE", "Workflow name is required", "definition.name", ValidationError.Severity.ERROR));
        }

        // Validate workflow has nodes
        if (workflowDefinition.nodes() == null || workflowDefinition.nodes().isEmpty()) {
            errors.add(new ValidationError("NODE_COUNT_RULE", "Workflow must have at least one node", "definition.nodes", ValidationError.Severity.ERROR));
        }

        // Validate that all nodes have unique IDs
        if (workflowDefinition.nodes() != null) {
            List<String> nodeIds = new ArrayList<>();
            for (var node : workflowDefinition.nodes()) {
                if (nodeIds.contains(node.nodeId())) {
                    errors.add(new ValidationError("UNIQUE_ID_RULE", "Duplicate node ID: " + node.nodeId(), "definition.nodes", ValidationError.Severity.ERROR));
                } else {
                    nodeIds.add(node.nodeId());
                }
            }
        }

        // Validate transitions
        if (workflowDefinition.transitions() != null) {
            for (var transition : workflowDefinition.transitions()) {
                if (transition.fromNodeId() == null || transition.toNodeId() == null) {
                    errors.add(new ValidationError("TRANSITION_RULE", "Transition must have both 'from' and 'to' nodes", "definition.transitions", ValidationError.Severity.ERROR));
                }
            }
        }

        if (errors.isEmpty()) {
            logger.debug("Workflow '{}' passed validation", workflowDefinition.name());
        } else {
            logger.warn("Workflow '{}' failed validation with {} errors", workflowDefinition.name(), errors.size());
        }

        return errors;
    }

    @Override
    public List<String> getValidationRules() {
        return List.of(
            "WORKFLOW_NAME_RULE: Validates that workflow has a name",
            "NODE_COUNT_RULE: Validates that workflow has at least one node",
            "UNIQUE_ID_RULE: Validates that all nodes have unique IDs",
            "TRANSITION_RULE: Validates that transitions have both from and to nodes"
        );
    }
}