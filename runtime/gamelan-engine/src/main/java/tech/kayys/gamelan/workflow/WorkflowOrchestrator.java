package tech.kayys.gamelan.workflow;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.collaboration.CollaborationContext;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.execution.ExecutionPlan;
import tech.kayys.gamelan.engine.node.DefaultNodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeDispatchReservation;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.registry.ExecutorRegistryService;
import tech.kayys.gamelan.registry.ExecutorSelectionRequest;
import tech.kayys.gamelan.registry.ExecutorSelectionReport;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.tenant.TenantId;

import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Core orchestrator that coordinates planning and dispatching
 */
@Startup
@ApplicationScoped
public class WorkflowOrchestrator {

    static final String RESULTS_ADDRESS = "gamelan.results";
    static final String RUN_UPDATED_ADDRESS = WorkflowRunUpdateEvent.ADDRESS;
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowOrchestrator.class);
    private final Object driveLock = new Object();
    private final Set<String> drivingRuns = new HashSet<>();
    private final Set<String> pendingDriveRuns = new HashSet<>();

    @Inject
    EventBus eventBus;

    @Inject
    WorkflowRunWakeupPublisher wakeupPublisher;

    @Inject
    WorkflowRunManager runManager;

    @Inject
    WorkflowRunRepository runRepository;

    @Inject
    WorkflowDefinitionRegistry definitionRegistry;

    @Inject
    WorkflowExecutionEngine executionEngine;

    @Inject
    ExecutorRegistryService executorRegistry;

    @Inject
    tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator taskDispatcher;

    @Inject
    TaskAdmissionController admissionController;

    @ConfigProperty(name = "gamelan.orchestrator.no-executor-policy", defaultValue = "fail")
    String noExecutorPolicy;

    @ConfigProperty(name = "gamelan.orchestrator.max-ready-nodes-per-cycle", defaultValue = "256")
    int maxReadyNodesPerCycle;

    @ConfigProperty(name = "gamelan.orchestrator.max-concurrent-dispatches", defaultValue = "64")
    int maxConcurrentDispatches;

    @jakarta.annotation.PostConstruct
    void init() {
        LOG.info("Initializing WorkflowOrchestrator");

        // 1. Listen for results from executors
        eventBus.<JsonObject>consumer(RESULTS_ADDRESS)
                .handler(msg -> handleNodeResult(msg.body())
                        .subscribe().with(
                                v -> LOG.debug("Result event handled"),
                                error -> LOG.error("Failed to handle result event", error)));

        // 2. Listen for run updates to drive the workflow
        eventBus.<Object>consumer(RUN_UPDATED_ADDRESS)
                .handler(msg -> handleRunUpdate(msg.body())
                        .subscribe().with(
                                v -> LOG.debug("Run update event handled"),
                                error -> LOG.error("Failed to handle run update event", error)));
    }

    Uni<Void> handleNodeResult(JsonObject payload) {
        return Uni.createFrom().deferred(() -> {
            Optional<NodeExecutionResult> result = decodeNodeResult(payload);
            if (result.isEmpty()) {
                return Uni.createFrom().voidItem();
            }

            NodeExecutionResult nodeResult = result.get();
            Optional<TenantId> tenantId = decodeTenant(payload);
            LOG.info("Received node result: run={}, node={}, status={}",
                    nodeResult.runId().value(), nodeResult.nodeId().value(), nodeResult.status());
            return tenantId.isPresent()
                    ? runManager.handleNodeResult(nodeResult.runId(), tenantId.get(), nodeResult).replaceWithVoid()
                    : runManager.handleNodeResult(nodeResult.runId(), nodeResult).replaceWithVoid();
        });
    }

    Uni<Void> handleRunUpdate(Object payload) {
        return Uni.createFrom().deferred(() -> {
            Optional<RunUpdateSignal> signal = decodeRunUpdate(payload);
            if (signal.isEmpty()) {
                return Uni.createFrom().voidItem();
            }

            RunUpdateSignal runUpdate = signal.get();
            LOG.info("Driving workflow run: {}", runUpdate.runId().value());
            return drive(runUpdate.runId(), runUpdate.tenantId().orElse(null))
                    .invoke(() -> LOG.info("Drive cycle completed for run: {}", runUpdate.runId().value()));
        });
    }

    private Optional<NodeExecutionResult> decodeNodeResult(JsonObject payload) {
        if (payload == null || payload.isEmpty()) {
            LOG.warn("Ignoring empty node result event");
            return Optional.empty();
        }
        try {
            JsonObject resultPayload = payload.copy();
            resultPayload.remove("tenantId");
            return Optional.of(resultPayload.mapTo(DefaultNodeExecutionResult.class));
        } catch (RuntimeException error) {
            LOG.warn("Ignoring malformed node result event: {}", error.getMessage());
            LOG.debug("Malformed node result event payload={}", payload.encode(), error);
            return Optional.empty();
        }
    }

    private Optional<TenantId> decodeTenant(JsonObject payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String tenantId = payload.getString("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TenantId.of(tenantId));
        } catch (RuntimeException error) {
            LOG.warn("Ignoring invalid node result tenant id: {}", tenantId);
            return Optional.empty();
        }
    }

    private Optional<RunUpdateSignal> decodeRunUpdate(Object payload) {
        if (payload instanceof WorkflowRunUpdateEvent event) {
            return Optional.of(new RunUpdateSignal(event.workflowRunId(), event.tenant()));
        }
        if (payload instanceof JsonObject json) {
            return decodeRunUpdate(json);
        }
        if (payload instanceof String runIdValue) {
            return decodeLegacyRunId(runIdValue);
        }
        if (payload == null) {
            LOG.warn("Ignoring run update event without run id");
        } else {
            LOG.warn("Ignoring unsupported run update payload type: {}", payload.getClass().getName());
        }
        return Optional.empty();
    }

    private Optional<RunUpdateSignal> decodeRunUpdate(JsonObject payload) {
        if (payload == null || payload.isEmpty()) {
            LOG.warn("Ignoring empty run update event");
            return Optional.empty();
        }
        try {
            WorkflowRunUpdateEvent event = payload.mapTo(WorkflowRunUpdateEvent.class);
            return Optional.of(new RunUpdateSignal(event.workflowRunId(), event.tenant()));
        } catch (RuntimeException error) {
            LOG.warn("Ignoring malformed run update event: {}", error.getMessage());
            LOG.debug("Malformed run update event payload={}", payload.encode(), error);
            return Optional.empty();
        }
    }

    private Optional<RunUpdateSignal> decodeLegacyRunId(String runIdValue) {
        if (runIdValue == null || runIdValue.isBlank()) {
            LOG.warn("Ignoring run update event without run id");
            return Optional.empty();
        }
        try {
            return Optional.of(new RunUpdateSignal(WorkflowRunId.of(runIdValue.trim()), Optional.empty()));
        } catch (RuntimeException error) {
            LOG.warn("Ignoring malformed run update event for run id '{}': {}", runIdValue, error.getMessage());
            LOG.debug("Malformed run update event details", error);
            return Optional.empty();
        }
    }

    /**
     * Drive the workflow cycle: Plan -> Select Executor -> Dispatch
     */
    public Uni<Void> drive(WorkflowRunId runId) {
        return drive(runId, null);
    }

    private Uni<Void> drive(WorkflowRunId runId, TenantId tenantId) {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        return Uni.createFrom().deferred(() -> {
            String key = driveKey(runId, tenantId);
            synchronized (driveLock) {
                if (drivingRuns.contains(key)) {
                    pendingDriveRuns.add(key);
                    LOG.debug("Coalescing drive request for already active run: {}", key);
                    return Uni.createFrom().voidItem();
                }
                drivingRuns.add(key);
            }
            return driveCoalesced(runId, tenantId, key);
        });
    }

    private String driveKey(WorkflowRunId runId, TenantId tenantId) {
        return tenantId != null ? tenantId.value() + ":" + runId.value() : runId.value();
    }

    private Uni<Void> driveCoalesced(WorkflowRunId runId, TenantId tenantId, String key) {
        return driveOnce(runId, tenantId)
                .onCancellation().invoke(() -> clearDriveState(key))
                .onItemOrFailure().transformToUni((ignored, failure) -> {
                    boolean runAgain;
                    synchronized (driveLock) {
                        runAgain = pendingDriveRuns.remove(key);
                        if (!runAgain) {
                            drivingRuns.remove(key);
                        }
                    }

                    if (runAgain) {
                        if (failure != null) {
                            LOG.warn("Drive cycle failed for run {}; running coalesced follow-up anyway: {}",
                                    key,
                                    failure.getMessage());
                            LOG.debug("Coalesced drive failure details", failure);
                        }
                        return driveCoalesced(runId, tenantId, key);
                    }

                    if (failure != null) {
                        return Uni.createFrom().failure(failure);
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    private void clearDriveState(String key) {
        synchronized (driveLock) {
            pendingDriveRuns.remove(key);
            drivingRuns.remove(key);
        }
    }

    private Uni<Void> driveOnce(WorkflowRunId runId, TenantId tenantId) {
        Uni<WorkflowRun> runLookup = tenantId != null
                ? runRepository.findById(runId, tenantId)
                : runRepository.findById(runId);
        return runLookup
                .flatMap(run -> {
                    if (run == null || run.getStatus().isTerminal()) {
                        return Uni.createFrom().voidItem();
                    }

                    if (run.getStatus() != RunStatus.RUNNING) {
                        return Uni.createFrom().voidItem();
                    }

                    return definitionRegistry.getDefinition(run.getDefinitionId(), run.getTenantId())
                            .flatMap(definition -> {
                                return executionEngine.planNextExecution(run, definition)
                                        .onFailure().recoverWithUni(error -> failRunForPlanningFailure(
                                                run,
                                                definition,
                                                error)
                                                .replaceWith(new ExecutionPlan(List.of(), false, false, Map.of())))
                                        .flatMap(plan -> {
                                            if (plan.isComplete()) {
                                                LOG.info("Workflow complete: {}", run.getId().value());
                                                return runManager.completeRun(runId, run.getTenantId(), plan.outputs())
                                                        .replaceWithVoid();
                                            }

                                            List<NodeId> plannedReadyNodes = plan.readyNodes() != null
                                                    ? plan.readyNodes()
                                                    : List.of();
                                            Map<NodeId, NodeDefinition> nodeIndex = indexNodes(definition);
                                            List<NodeId> readyNodes = normalizeReadyNodes(plannedReadyNodes, nodeIndex);

                                            if (readyNodes.isEmpty()) {
                                                if (plan.isStuck()) {
                                                    LOG.warn("Workflow stuck: {}", run.getId().value());
                                                    return runManager.failRun(
                                                            run.getId(),
                                                            run.getTenantId(),
                                                            stuckWorkflowError(run, definition))
                                                            .replaceWithVoid();
                                                } else if (!plannedReadyNodes.isEmpty()) {
                                                    LOG.warn("Planner returned no dispatchable ready nodes for run={}",
                                                            run.getId().value());
                                                }
                                                return Uni.createFrom().voidItem();
                                            }

                                            List<NodeId> dispatchBatch = dispatchBatch(readyNodes);
                                            boolean hasMoreReadyNodes = readyNodes.size() > dispatchBatch.size();

                                            return dispatchNodes(run, definition, nodeIndex, dispatchBatch)
                                                    .call(() -> {
                                                        if (!hasMoreReadyNodes) {
                                                            return Uni.createFrom().voidItem();
                                                        }
                                                        LOG.debug(
                                                                "Ready-node batch capped for run={}, dispatched={}, remaining={}",
                                                                runId.value(),
                                                                dispatchBatch.size(),
                                                                readyNodes.size() - dispatchBatch.size());
                                                        return publishRunUpdate(
                                                                run.getId(),
                                                                run.getTenantId(),
                                                                "ready-batch-remaining");
                                                    });
                                        });
                            });
                });
    }

    private Uni<Void> failRunForPlanningFailure(
            WorkflowRun run,
            WorkflowDefinition definition,
            Throwable error) {
        LOG.warn("Workflow planning failed for run={}: {}", run.getId().value(), safeMessage(error));
        LOG.debug("Workflow planning failure details", error);
        return runManager.failRun(
                run.getId(),
                run.getTenantId(),
                planningFailureError(run, definition, error))
                .replaceWithVoid();
    }

    private Map<NodeId, NodeDefinition> indexNodes(WorkflowDefinition definition) {
        Map<NodeId, NodeDefinition> nodeIndex = new HashMap<>();
        for (NodeDefinition node : definition.nodes()) {
            nodeIndex.putIfAbsent(node.id(), node);
        }
        return Map.copyOf(nodeIndex);
    }

    private ErrorInfo planningFailureError(
            WorkflowRun run,
            WorkflowDefinition definition,
            Throwable error) {
        Map<String, Object> context = new HashMap<>();
        context.put("runId", run.getId().value());
        context.put("tenantId", run.getTenantId().value());
        context.put("definitionId", definition.id().value());
        context.put("mode", definition.mode().name());
        context.put("errorType", error.getClass().getSimpleName());

        if (error instanceof WorkflowPlanningException planningError) {
            context.put("validationErrors", planningError.validationErrors());
            context.put("planningDefinitionId", planningError.definitionId());
            context.put("planningTenantId", planningError.tenantId());
            return new ErrorInfo(
                    "WORKFLOW_DEFINITION_INVALID",
                    safeMessage(error),
                    "",
                    context);
        }

        return new ErrorInfo(
                "WORKFLOW_PLANNING_FAILED",
                "Workflow planning failed: " + safeMessage(error),
                "",
                context);
    }

    private ErrorInfo stuckWorkflowError(WorkflowRun run, WorkflowDefinition definition) {
        Map<String, Object> context = new HashMap<>();
        context.put("definitionId", definition.id().value());
        context.put("mode", definition.mode().name());
        context.put("unresolvedNodes", unresolvedNodeIds(run, definition));
        context.put("activeNodes", nodesByStatus(run, Set.of(
                NodeExecutionStatus.PENDING,
                NodeExecutionStatus.RUNNING,
                NodeExecutionStatus.EXECUTING,
                NodeExecutionStatus.WAITING,
                NodeExecutionStatus.RETRYING)));
        context.put("failedNodes", nodesByStatus(run, Set.of(NodeExecutionStatus.FAILED)));
        return new ErrorInfo(
                "WORKFLOW_STUCK",
                "Workflow cannot progress; unresolved nodes remain blocked",
                "",
                context);
    }

    private List<String> unresolvedNodeIds(WorkflowRun run, WorkflowDefinition definition) {
        return definition.nodes().stream()
                .filter(node -> {
                    NodeExecution execution = run.getAllNodeExecutions().get(node.id());
                    if (execution == null) {
                        return true;
                    }
                    return !execution.isCompleted() && !(execution.isFailed() && !node.isCritical());
                })
                .map(node -> node.id().value())
                .toList();
    }

    private List<String> nodesByStatus(WorkflowRun run, Set<NodeExecutionStatus> statuses) {
        return run.getAllNodeExecutions().values().stream()
                .filter(execution -> statuses.contains(execution.getStatus()))
                .map(execution -> execution.getNodeId().value())
                .toList();
    }

    private List<NodeId> normalizeReadyNodes(
            List<NodeId> readyNodes,
            Map<NodeId, NodeDefinition> nodeIndex) {
        if (readyNodes == null || readyNodes.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<NodeId> uniqueReadyNodes = new LinkedHashSet<>();
        for (NodeId nodeId : readyNodes) {
            if (nodeId == null) {
                LOG.warn("Ignoring null ready node from planner");
            } else if (!nodeIndex.containsKey(nodeId)) {
                LOG.warn("Ignoring unknown ready node from planner: {}", nodeId.value());
            } else if (!uniqueReadyNodes.add(nodeId)) {
                LOG.debug("Ignoring duplicate ready node from planner: {}", nodeId.value());
            }
        }
        return List.copyOf(uniqueReadyNodes);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }
        String message = error.getMessage();
        return message != null && !message.isBlank()
                ? message
                : error.getClass().getSimpleName();
    }

    private List<NodeId> dispatchBatch(List<NodeId> readyNodes) {
        if (readyNodes == null || readyNodes.isEmpty()) {
            return List.of();
        }
        int limit = effectiveReadyNodeLimit();
        if (readyNodes.size() <= limit) {
            return readyNodes;
        }
        return List.copyOf(readyNodes.subList(0, limit));
    }

    private int effectiveReadyNodeLimit() {
        return maxReadyNodesPerCycle > 0 ? maxReadyNodesPerCycle : 256;
    }

    private Uni<Void> dispatchNodes(
            WorkflowRun run,
            WorkflowDefinition definition,
            Map<NodeId, NodeDefinition> nodeIndex,
            List<NodeId> dispatchBatch) {
        if (dispatchBatch == null || dispatchBatch.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        int concurrency = effectiveMaxConcurrentDispatches();
        Uni<Void> dispatchChain = Uni.createFrom().voidItem();
        for (int start = 0; start < dispatchBatch.size(); start += concurrency) {
            int end = Math.min(start + concurrency, dispatchBatch.size());
            List<NodeId> chunk = List.copyOf(dispatchBatch.subList(start, end));
            dispatchChain = dispatchChain.chain(() -> dispatchNodeChunk(run, definition, nodeIndex, chunk));
        }
        return dispatchChain;
    }

    private Uni<Void> dispatchNodeChunk(
            WorkflowRun run,
            WorkflowDefinition definition,
            Map<NodeId, NodeDefinition> nodeIndex,
            List<NodeId> chunk) {
        return Uni.combine().all().unis(
                chunk.stream()
                        .map(nodeId -> dispatchNode(run, definition, nodeIndex.get(nodeId)))
                        .toList())
                .discardItems();
    }

    private int effectiveMaxConcurrentDispatches() {
        int readyLimit = effectiveReadyNodeLimit();
        int configuredLimit = maxConcurrentDispatches > 0 ? maxConcurrentDispatches : 64;
        return Math.max(1, Math.min(configuredLimit, readyLimit));
    }

    private Uni<Void> dispatchNode(WorkflowRun run, WorkflowDefinition definition, NodeDefinition node) {
        if (node == null) {
            return Uni.createFrom().voidItem();
        }
        NodeId nodeId = node.id();

        if (taskAdmissionController().shouldResolveBeforeReservation(noExecutorPolicy)) {
            return resolveExecutorBeforeReservation(run, definition, node, nodeId);
        }

        return reserveNodeForDispatch(run.getId(), run.getTenantId(), nodeId)
                .flatMap(reservation -> {
                    if (!reservation.reserved()) {
                        LOG.debug("Node already claimed, skipping executor selection: run={}, node={}",
                                run.getId().value(), nodeId.value());
                        return Uni.createFrom().voidItem();
                    }

                    int attempt = reservation.attempt();
                    return resolveExecutor(node)
                            .flatMap(resolution -> {
                                if (resolution.executor().isEmpty()) {
                                    LOG.warn("No executor available for node: {}", nodeId.value());
                                    TaskAdmissionDecision decision = taskAdmissionController().noExecutor(
                                            node,
                                            resolution.selectionReport(),
                                            noExecutorPolicy);
                                    recordAdmission(decision);
                                    if (decision.shouldLeavePending()) {
                                        return Uni.createFrom().voidItem();
                                    }
                                    return markReservedNoExecutorAvailable(
                                            run.getId(),
                                            run.getTenantId(),
                                            node,
                                            attempt,
                                            decision);
                                }
                                TaskAdmissionDecision decision = taskAdmissionController().executorSelected(
                                        node,
                                        resolution.executor().get(),
                                        resolution.selectionReport(),
                                        noExecutorPolicy);
                                recordAdmission(decision);
                                return dispatchReservedNode(
                                        run,
                                        definition,
                                        node,
                                        resolution.executor().get(),
                                        attempt);
                            })
                            .onFailure().call(error -> markDispatchFailure(
                                    run.getId(),
                                    run.getTenantId(),
                                    nodeId,
                                    attempt,
                                    error))
                            .onFailure().invoke(error -> logDispatchFailure(run, nodeId, attempt, error))
                            .onFailure().recoverWithNull();
                });
    }

    private Uni<Void> resolveExecutorBeforeReservation(
            WorkflowRun run,
            WorkflowDefinition definition,
            NodeDefinition node,
            NodeId nodeId) {
        return resolveExecutor(node)
                .flatMap(resolution -> {
                    if (resolution.executor().isEmpty()) {
                        LOG.warn("No executor available for node: {}", nodeId.value());
                        TaskAdmissionDecision decision = taskAdmissionController().noExecutor(
                                node,
                                resolution.selectionReport(),
                                noExecutorPolicy);
                        recordAdmission(decision);
                        if (decision.shouldLeavePending()) {
                            return Uni.createFrom().voidItem();
                        }
                        return markNoExecutorAvailable(
                                run.getId(),
                                run.getTenantId(),
                                node,
                                decision);
                    }

                    ExecutorInfo executor = resolution.executor().get();

                    return reserveNodeForDispatch(run.getId(), run.getTenantId(), nodeId)
                            .flatMap(reservation -> {
                                if (!reservation.reserved()) {
                                    LOG.debug("Node already claimed, skipping dispatch: run={}, node={}",
                                            run.getId().value(), nodeId.value());
                                    return Uni.createFrom().voidItem();
                                }

                                int attempt = reservation.attempt();
                                TaskAdmissionDecision decision = taskAdmissionController().executorSelected(
                                        node,
                                        executor,
                                        resolution.selectionReport(),
                                        noExecutorPolicy);
                                recordAdmission(decision);
                                return dispatchReservedNode(run, definition, node, executor, attempt)
                                        .onFailure().call(error -> markDispatchFailure(
                                                run.getId(),
                                                run.getTenantId(),
                                                nodeId,
                                                attempt,
                                                error))
                                        .onFailure().invoke(error -> logDispatchFailure(run, nodeId, attempt, error))
                                        .onFailure().recoverWithNull();
                            });
        });
    }

    private Uni<Void> dispatchReservedNode(
            WorkflowRun run,
            WorkflowDefinition definition,
            NodeDefinition node,
            ExecutorInfo executor,
            int attempt) {
        NodeId nodeId = node.id();
        return runManager.createExecutionToken(run.getId(), run.getTenantId(), nodeId, attempt)
                .flatMap(token -> {
                    NodeExecutionTask task = new NodeExecutionTask(
                            run.getId(),
                            nodeId,
                            attempt,
                            token,
                            buildTaskContext(run, definition, node, attempt),
                            node.retryPolicy());

                    return taskDispatcher.dispatch(task, executor);
                });
    }

    private void logDispatchFailure(
            WorkflowRun run,
            NodeId nodeId,
            int attempt,
            Throwable error) {
        LOG.error(
                "Dispatch failed for run={}, node={}, attempt={}",
                run.getId().value(),
                nodeId.value(),
                attempt,
                error);
    }

    private Uni<Void> markNoExecutorAvailable(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeDefinition node,
            TaskAdmissionDecision decision) {
        return runManager.reserveNodeForDispatch(runId, tenantId, node.id())
                .flatMap(reservation -> reservation.reserved()
                        ? runManager.failNodeExecution(
                                runId,
                                tenantId,
                                node.id(),
                                reservation.attempt(),
                                noExecutorError(node, decision),
                                ExecutorSelectionRejectionReasons.NO_EXECUTOR)
                        : Uni.createFrom().voidItem());
    }

    private Uni<Void> markReservedNoExecutorAvailable(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeDefinition node,
            int attempt,
            TaskAdmissionDecision decision) {
        return runManager.failNodeExecution(
                runId,
                tenantId,
                node.id(),
                attempt,
                noExecutorError(node, decision),
                ExecutorSelectionRejectionReasons.NO_EXECUTOR);
    }

    private Map<String, Object> buildTaskContext(
            WorkflowRun run,
            WorkflowDefinition definition,
            NodeDefinition node,
            int attempt) {
        Map<String, Object> workflowVariables = new HashMap<>(run.getContext().getVariables());
        Map<String, Object> nodeConfiguration = new HashMap<>(node.configuration());
        Map<String, Object> taskContext = new HashMap<>(node.configuration());

        taskContext.put(NodeExecutionTask.RUN_ID_KEY, run.getId().value());
        taskContext.put(NodeExecutionTask.WORKFLOW_DEFINITION_ID_KEY, definition.id().value());
        taskContext.put(NodeExecutionTask.TENANT_ID_KEY, run.getTenantId().value());
        taskContext.put(NodeExecutionTask.NODE_ID_KEY, node.id().value());
        taskContext.put(NodeExecutionTask.NODE_NAME_KEY, node.name());
        taskContext.putIfAbsent(NodeExecutionTask.NODE_TYPE_KEY, node.executorType());
        taskContext.put(NodeExecutionTask.ATTEMPT_KEY, attempt);
        taskContext.put(NodeExecutionTask.NODE_CONFIGURATION_KEY, nodeConfiguration);
        taskContext.put(NodeExecutionTask.WORKFLOW_VARIABLES_KEY, workflowVariables);
        long timeoutSeconds = timeoutSeconds(node);
        if (timeoutSeconds > 0) {
            taskContext.put(NodeExecutionTask.TIMEOUT_SECONDS_KEY, timeoutSeconds);
        }
        taskContext.putIfAbsent(NodeExecutionTask.LEGACY_CONTEXT_KEY, workflowVariables);

        return taskContext;
    }

    private long timeoutSeconds(NodeDefinition node) {
        return node.timeout() != null && !node.timeout().isZero() && !node.timeout().isNegative()
                ? node.timeout().toSeconds()
                : 0L;
    }

    private Uni<NodeDispatchReservation> reserveNodeForDispatch(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId) {
        return runManager.reserveNodeForDispatch(runId, tenantId, nodeId);
    }

    private Uni<Void> markDispatchFailure(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Throwable error) {
        return runManager.failNodeExecution(
                runId,
                tenantId,
                nodeId,
                attempt,
                ErrorInfo.of(error),
                "dispatch-failed");
    }

    private Uni<Void> publishRunUpdate(
            WorkflowRunId runId,
            TenantId tenantId,
            String reason) {
        WorkflowRunUpdateEvent event = WorkflowRunUpdateEvent.of(runId, tenantId, reason);
        if (wakeupPublisher != null) {
            return wakeupPublisher.publish(event);
        }
        if (eventBus != null) {
            eventBus.publish(RUN_UPDATED_ADDRESS, JsonObject.mapFrom(event));
            return Uni.createFrom().voidItem();
        }
        LOG.warn("No workflow run wake-up publisher available for run={}, reason={}",
                event.runId(), event.reason());
        return Uni.createFrom().voidItem();
    }

    private TaskAdmissionController taskAdmissionController() {
        return admissionController != null ? admissionController : new TaskAdmissionController();
    }

    private void recordAdmission(TaskAdmissionDecision decision) {
        taskAdmissionController().record(decision);
    }

    private ErrorInfo noExecutorError(
            NodeDefinition node,
            TaskAdmissionDecision decision) {
        String executorType = node.executorType() != null ? node.executorType() : "";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeId", node.id().value());
        metadata.put("executorType", executorType);
        metadata.put(
                "policy",
                decision != null ? decision.policy() : (noExecutorPolicy != null ? noExecutorPolicy : "fail"));

        ExecutorPlacementRequirements placement = placementRequirements(node);
        if (!placement.isEmpty()) {
            metadata.put("placement", placement.toContextMap());
        }
        if (decision != null) {
            metadata.putAll(decision.toErrorContext());
        }

        return new ErrorInfo(
                "NO_EXECUTOR_AVAILABLE",
                "No executor available for node " + node.id().value(),
                "",
                metadata);
    }

    private Uni<ExecutorResolution> resolveExecutor(NodeDefinition node) {
        ExecutorPlacementRequirements placement = placementRequirements(node);
        Map<String, Object> selectionContext = executorSelectionContext(node);
        String executorType = node.executorType();
        if (isSpecificExecutorType(executorType)) {
            return executorRegistry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                    node.id(),
                    executorType,
                    placement,
                    selectionContext))
                    .map(ExecutorResolution::fromReport);
        }
        return executorRegistry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNode(
                node.id(),
                placement,
                selectionContext))
                .map(ExecutorResolution::fromReport);
    }

    private boolean isSpecificExecutorType(String executorType) {
        return executorType != null
                && !executorType.isBlank()
                && !"unspecified".equalsIgnoreCase(executorType);
    }

    private ExecutorPlacementRequirements placementRequirements(NodeDefinition node) {
        return ExecutorPlacementRequirements.fromContext(CollaborationContext.fromContextValue(
                node.configuration().get(NodeExecutionTask.COLLABORATION_CONTEXT_KEY)));
    }

    private Map<String, Object> executorSelectionContext(NodeDefinition node) {
        return ExecutorSelectionPolicy.fromContext(node.configuration()).toSelectionContext();
    }

    private record ExecutorResolution(
            Optional<ExecutorInfo> executor,
            ExecutorSelectionReport selectionReport) {

        private static ExecutorResolution fromReport(ExecutorSelectionReport report) {
            return new ExecutorResolution(report.selectedExecutor(), report);
        }
    }

    private record RunUpdateSignal(
            WorkflowRunId runId,
            Optional<TenantId> tenantId) {

        private RunUpdateSignal {
            Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
            tenantId = tenantId != null ? tenantId : Optional.empty();
        }
    }
}
