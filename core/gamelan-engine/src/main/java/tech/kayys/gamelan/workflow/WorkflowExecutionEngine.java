package tech.kayys.gamelan.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.gamelan.engine.execution.ExecutionPlan;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.plugin.PluginService;

/**
 * Core execution engine that evaluates workflow progress
 * and determines next steps
 */
@ApplicationScoped
public class WorkflowExecutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExecutionEngine.class);

    @Inject
    WorkflowDefinitionRegistry definitionRegistry;

    @Inject
    Instance<PluginService> pluginService;

    @ConfigProperty(name = "gamelan.dag.scheduler.enabled", defaultValue = "false")
    boolean dagSchedulerEnabled;

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

            if (definition.mode() == WorkflowMode.DAG && dagSchedulerEnabled) {
                readyNodes = orderDagReadyNodes(definition, readyNodes);
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

    private List<NodeId> orderDagReadyNodes(WorkflowDefinition definition, List<NodeId> readyNodes) {
        if (readyNodes == null || readyNodes.isEmpty()) {
            return readyNodes;
        }
        if (pluginService == null || !pluginService.isResolvable()) {
            return readyNodes;
        }
        try {
            Class<?> serviceClass = Class.forName("tech.kayys.gamelan.dag.DagSchedulerService");
            PluginService service = pluginService.get();
            var optional = service.getService(serviceClass);
            if (optional.isEmpty()) {
                return readyNodes;
            }
            Object scheduler = optional.get();
            var method = serviceClass.getMethod("orderReadyNodes", WorkflowDefinition.class, List.class);
            Object result = method.invoke(scheduler, definition, readyNodes);
            if (result instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<NodeId> ordered = (List<NodeId>) list;
                return ordered;
            }
        } catch (Exception e) {
            LOG.warn("DAG scheduler not available, using default ordering", e);
        }
        return readyNodes;
    }

    /**
     * Check if a node is ready to execute
     */
    private boolean isNodeReady(WorkflowRun run, NodeDefinition node) {
        // Check if node already executed
        Map<NodeId, NodeExecution> executions = run.getAllNodeExecutions();
        NodeExecution existing = executions.get(node.id());

        if (existing != null) {
            // Only retry if in RETRYING status, or if PENDING (waiting for dispatch)
            return existing.getStatus() == NodeExecutionStatus.RETRYING ||
                    existing.getStatus() == NodeExecutionStatus.PENDING;
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
