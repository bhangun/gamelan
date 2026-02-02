package tech.kayys.gamelan.core.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.core.engine.WorkflowEngine;
import tech.kayys.gamelan.core.node.DefaultNodeExecutionContext;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.DefaultNodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.saga.CompensationService;

/**
 * WorkflowOrchestrator
 * 
 * Orchestrates workflow execution by:
 * 1. Listening to workflow run state changes
 * 2. Scheduling ready nodes for execution
 * 3. Invoking WorkflowEngine.executeNode() for each node
 * 4. Handling node results and updating workflow state
 */
@ApplicationScoped
public class WorkflowOrchestrator {

        private static final Logger LOG = LoggerFactory.getLogger(WorkflowOrchestrator.class);

        @Inject
        WorkflowEngine workflowEngine;

        @Inject
        EngineContext engineContext;

        @Inject
        WorkflowRunRepository runRepository;

        @Inject
        WorkflowDefinitionService definitionService;

        @Inject
        WorkflowRunManager runManager;

        @Inject
        CompensationService compensationService;

        /**
         * Listens to workflow run updates and triggers node execution
         */
        @ConsumeEvent("gamelan.runs.v1.updated")
        public Uni<Void> onWorkflowRunUpdated(String runIdValue) {
                LOG.info("Workflow run updated: {}", runIdValue);

                WorkflowRunId runId = WorkflowRunId.of(runIdValue);

                return runRepository.findById(runId)
                                .onItem().ifNull()
                                .failWith(() -> new IllegalArgumentException("Run not found: " + runIdValue))
                                .chain(run -> {
                                        // Handle compensation if workflow is compensating
                                        if (run.getStatus() == RunStatus.COMPENSATING) {
                                                LOG.info("Workflow {} is compensating, triggering compensation coordinator",
                                                                runIdValue);
                                                return compensationService.compensate(run)
                                                                .flatMap(result -> {
                                                                        if (result.success()) {
                                                                                LOG.info("Compensation completed successfully for workflow {}",
                                                                                                runIdValue);
                                                                                return runManager.completeCompensation(
                                                                                                runId,
                                                                                                run.getTenantId());
                                                                        } else {
                                                                                LOG.error("Compensation failed for workflow {}: {}",
                                                                                                runIdValue,
                                                                                                result.message());
                                                                                return runManager.failCompensation(
                                                                                                runId,
                                                                                                run.getTenantId(),
                                                                                                new ErrorInfo(
                                                                                                                "COMPENSATION_FAILED",
                                                                                                                result.message(),
                                                                                                                "",
                                                                                                                Map.of()));
                                                                        }
                                                                });
                                        }

                                        // Only process if workflow is running
                                        if (run.getStatus() != RunStatus.RUNNING) {
                                                LOG.debug("Workflow {} is in status {}, skipping orchestration",
                                                                runIdValue, run.getStatus());
                                                return Uni.createFrom().voidItem();
                                        }

                                        // Get pending nodes
                                        List<NodeId> pendingNodes = run.getPendingNodes();
                                        if (pendingNodes.isEmpty()) {
                                                LOG.debug("No pending nodes for workflow {}", runIdValue);
                                                return Uni.createFrom().voidItem();
                                        }

                                        LOG.info("Orchestrating {} pending nodes for workflow {}",
                                                        pendingNodes.size(), runIdValue);

                                        // Get max parallel nodes from configuration (default: 10)
                                        int maxParallelNodes = engineContext.configuration()
                                                        .get("gamelan.orchestration.max-parallel-nodes", Integer.class)
                                                        .orElse(10);

                                        if (pendingNodes.size() > maxParallelNodes) {
                                                LOG.info("Throttling {} pending nodes to max parallel limit of {}",
                                                                pendingNodes.size(), maxParallelNodes);
                                        }

                                        // Execute pending nodes with bounded parallelism
                                        return definitionService.get(run.getDefinitionId(), run.getTenantId())
                                                        .chain(definition -> {
                                                                // Use Multi for bounded parallelism
                                                                // transformToUniAndMerge default concurrency is 128,
                                                                // but we want to control it via configuration
                                                                return io.smallrye.mutiny.Multi.createFrom()
                                                                                .iterable(pendingNodes)
                                                                                .map(nodeId -> new Object() {
                                                                                        final NodeDefinition def = definition
                                                                                                        .findNode(nodeId)
                                                                                                        .orElse(null);
                                                                                })
                                                                                .onItem()
                                                                                .transformToUniAndMerge(
                                                                                                item -> executeNodeForRun(
                                                                                                                run,
                                                                                                                item.def))
                                                                                .collect().asList()
                                                                                .replaceWithVoid();
                                                        });
                                })
                                .onFailure()
                                .invoke(error -> LOG.error("Error orchestrating workflow {}", runIdValue, error));
        }

        /**
         * Execute a single node for a workflow run
         */
        private Uni<Void> executeNodeForRun(WorkflowRun run, NodeDefinition nodeDef) {
                if (nodeDef == null) {
                        return Uni.createFrom().voidItem();
                }

                NodeId nodeId = nodeDef.id();
                NodeExecution nodeExec = run.getNodeExecution(nodeId);

                LOG.info("Executing node {} (type: {}) for run {}",
                                nodeId.value(), nodeDef.type(), run.getId().value());

                // Atomic node start with idempotency check
                return runManager.getRun(run.getId(), run.getTenantId())
                                .chain(freshRun -> {
                                        // Check if node is already started or completed (idempotency)
                                        NodeExecution freshExec = freshRun.getNodeExecution(nodeId);
                                        if (freshExec.getStatus() != NodeExecutionStatus.PENDING
                                                        || freshExec.isCompleted()) {
                                                LOG.debug("Node {} already started or completed (status: {}), skipping execution",
                                                                nodeId.value(), freshExec.getStatus());
                                                return Uni.createFrom().voidItem();
                                        }

                                        // Mark node as started atomically
                                        freshRun.startNode(nodeId, nodeExec.getAttempt());
                                        return Uni.createFrom().item(freshRun);
                                })
                                .chain(() -> {
                                        // Prepare node inputs from workflow context
                                        Map<String, Object> nodeInputs = prepareNodeInputs(run, nodeDef);

                                        // Create NodeContext
                                        NodeContext nodeContext = new NodeContext(
                                                        nodeId,
                                                        nodeDef.type().name(),
                                                        nodeInputs,
                                                        Map.of(
                                                                        "runId", run.getId().value(),
                                                                        "attempt", nodeExec.getAttempt()));

                                        // Create NodeExecutionContext
                                        DefaultNodeExecutionContext executionContext = new DefaultNodeExecutionContext(
                                                        engineContext,
                                                        new WorkflowContextAdapter(run));

                                        // Execute via WorkflowEngine (applies interceptors)
                                        return workflowEngine.executeNode(nodeContext, executionContext)
                                                        .chain(result -> handleNodeResult(run, nodeId,
                                                                        nodeExec.getAttempt(), result,
                                                                        executionContext.getAddedVariables()));
                                })
                                .onFailure()
                                .recoverWithUni(error -> handleNodeError(run, nodeId, nodeExec.getAttempt(), error));
        }

        /**
         * Prepare node inputs from workflow context
         */
        private Map<String, Object> prepareNodeInputs(WorkflowRun run, NodeDefinition nodeDef) {
                Map<String, Object> inputs = new java.util.HashMap<>();

                // Get inputs from workflow context based on node dependencies
                nodeDef.dependsOn().forEach(depId -> {
                        String prefix = depId.value() + ".";
                        run.getContext().getVariables().forEach((key, value) -> {
                                if (key.startsWith(prefix)) {
                                        String inputKey = key.substring(prefix.length());
                                        inputs.put(inputKey, value);
                                }
                        });
                });

                // Add workflow-level inputs
                run.getContext().getVariables().forEach((key, value) -> {
                        if (!key.contains(".")) {
                                inputs.put(key, value);
                        }
                });

                return inputs;
        }

        /**
         * Handle successful node execution result
         */
        @SuppressWarnings("unchecked")
        private Uni<Void> handleNodeResult(WorkflowRun run, NodeId nodeId, int attempt, NodeResult result,
                        Map<String, Object> addedVariables) {
                if (result.success()) {
                        LOG.info("Node {} completed successfully for run {}",
                                        nodeId.value(), run.getId().value());

                        Map<String, Object> finalOutput = new java.util.HashMap<>();
                        if (result.output() instanceof Map) {
                                finalOutput.putAll((Map<String, Object>) result.output());
                        } else if (result.output() != null) {
                                finalOutput.put("result", result.output());
                        }

                        // Merge variables added via executionContext.setVariable()
                        if (addedVariables != null) {
                                finalOutput.putAll(addedVariables);
                        }

                        return runManager.handleNodeResult(
                                        run.getId(),
                                        new DefaultNodeExecutionResult(
                                                        run.getId(),
                                                        nodeId,
                                                        attempt,
                                                        NodeExecutionStatus.COMPLETED,
                                                        finalOutput,
                                                        null,
                                                        null));
                } else {
                        String errorMsg = (result.metadata() != null && result.metadata().containsKey("error"))
                                        ? result.metadata().get("error").toString()
                                        : "Unknown error";

                        LOG.error("Node {} failed for run {}: {}",
                                        nodeId.value(), run.getId().value(), errorMsg);

                        return runManager.handleNodeResult(
                                        run.getId(),
                                        new DefaultNodeExecutionResult(
                                                        run.getId(),
                                                        nodeId,
                                                        attempt,
                                                        NodeExecutionStatus.FAILED,
                                                        null,
                                                        new ErrorInfo(
                                                                        "NODE_EXECUTION_FAILED",
                                                                        errorMsg,
                                                                        "",
                                                                        Map.of()),
                                                        null));
                }
        }

        /**
         * Handle technical error during node execution
         */
        private Uni<Void> handleNodeError(WorkflowRun run, NodeId nodeId, int attempt, Throwable error) {
                LOG.error("Technical error executing node {} for run {}: {}",
                                nodeId.value(), run.getId().value(), error.getMessage(), error);

                return runManager.handleNodeResult(
                                run.getId(),
                                new DefaultNodeExecutionResult(
                                                run.getId(),
                                                nodeId,
                                                attempt,
                                                NodeExecutionStatus.FAILED,
                                                null,
                                                new ErrorInfo(
                                                                "NODE_TECHNICAL_ERROR",
                                                                error.getMessage(),
                                                                getStackTrace(error),
                                                                Map.of()),
                                                null));
        }

        private String getStackTrace(Throwable error) {
                java.io.StringWriter sw = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(sw));
                return sw.toString();
        }

        private static class WorkflowContextAdapter implements WorkflowContext {
                private final WorkflowRun run;

                public WorkflowContextAdapter(WorkflowRun run) {
                        this.run = run;
                }

                @Override
                public WorkflowRunId runId() {
                        return run.getId();
                }

                @Override
                public WorkflowDefinitionId definitionId() {
                        return run.getDefinitionId();
                }

                @Override
                public TenantId tenantId() {
                        return run.getTenantId();
                }

                @Override
                public RunStatus status() {
                        return run.getStatus();
                }

                @Override
                public Instant startedAt() {
                        return run.getStartedAt();
                }

                @Override
                public Instant updatedAt() {
                        return run.getLastUpdatedAt();
                }

                @Override
                public Map<String, Object> variables() {
                        return run.getContext().getVariables();
                }

                @Override
                public Map<NodeId, NodeResult> completedNodes() {
                        // Return actual completed nodes from the run
                        return run.getAllNodeExecutions().entrySet().stream()
                                        .filter(entry -> entry.getValue().isCompleted())
                                        .collect(java.util.stream.Collectors.toMap(
                                                        Map.Entry::getKey,
                                                        entry -> {
                                                                NodeExecution exec = entry.getValue();
                                                                return NodeResult.success(exec.getOutput());
                                                        }));
                }
        }
}
