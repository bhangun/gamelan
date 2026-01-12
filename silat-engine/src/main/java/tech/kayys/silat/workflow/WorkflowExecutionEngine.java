package tech.kayys.silat.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.ExecutionPlan;
import tech.kayys.silat.execution.NodeExecutionStatus;
import tech.kayys.silat.model.NodeDefinition;
import tech.kayys.silat.model.NodeExecution;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.RunStatus;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowRun;

/**
 * Core execution engine that evaluates workflow progress
 * and determines next steps
 */
@ApplicationScoped
public class WorkflowExecutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExecutionEngine.class);

    @Inject
    WorkflowDefinitionRegistry definitionRegistry;

    /**
     * Evaluate workflow and determine next nodes to execute
     */
    public Uni<ExecutionPlan> planNextExecution(
            WorkflowRun run,
            WorkflowDefinition definition) {

        LOG.debug("Planning next execution for run: {}", run.getId().value());

        return Uni.createFrom().item(() -> {
            List<NodeId> readyNodes = new ArrayList<>();

            // Find all nodes that are ready to execute
            for (NodeDefinition node : definition.nodes()) {
                if (isNodeReady(run, node)) {
                    readyNodes.add(node.id());
                }
            }

            // Check for workflow completion
            boolean isComplete = isWorkflowComplete(run, definition);

            // Check if workflow is stuck
            boolean isStuck = readyNodes.isEmpty() && !isComplete &&
                    run.getStatus() == RunStatus.RUNNING;

            return new ExecutionPlan(
                    readyNodes,
                    isComplete,
                    isStuck,
                    collectWorkflowOutputs(run, definition));
        });
    }

    /**
     * Check if a node is ready to execute
     */
    private boolean isNodeReady(WorkflowRun run, NodeDefinition node) {
        // Check if node already executed
        Map<NodeId, NodeExecution> executions = run.getAllNodeExecutions();
        NodeExecution existing = executions.get(node.id());

        if (existing != null) {
            // Only retry if in RETRYING status
            return existing.getStatus() == NodeExecutionStatus.RETRYING;
        }

        // Check if all dependencies are completed
        for (NodeId depId : node.dependsOn()) {
            NodeExecution depExec = executions.get(depId);
            if (depExec == null || depExec.getStatus() != NodeExecutionStatus.COMPLETED) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if workflow is complete
     */
    private boolean isWorkflowComplete(WorkflowRun run, WorkflowDefinition definition) {
        Map<NodeId, NodeExecution> executions = run.getAllNodeExecutions();

        // All nodes must have been executed
        for (NodeDefinition node : definition.nodes()) {
            NodeExecution exec = executions.get(node.id());
            if (exec == null || !exec.isCompleted()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Collect workflow outputs from node executions
     */
    private Map<String, Object> collectWorkflowOutputs(
            WorkflowRun run,
            WorkflowDefinition definition) {

        Map<String, Object> outputs = new HashMap<>();

        // Collect outputs defined in workflow definition
        definition.outputs().forEach((outputName, outputDef) -> {
            // Try to find output in context variables
            Object value = run.getContext().getVariable(outputName);
            if (value != null) {
                outputs.put(outputName, value);
            }
        });

        return outputs;
    }
}
