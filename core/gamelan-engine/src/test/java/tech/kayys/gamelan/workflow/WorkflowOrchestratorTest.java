package tech.kayys.gamelan.workflow;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.EXECUTOR_TYPE_MISMATCH;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.NO_EXECUTOR;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.PLACEMENT_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.collaboration.CollaborationContext;
import tech.kayys.gamelan.engine.collaboration.ParticipantIsolation;
import tech.kayys.gamelan.engine.collaboration.ParticipantKind;
import tech.kayys.gamelan.engine.collaboration.ParticipantRuntime;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.execution.ExecutionPlan;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.node.NodeDispatchReservation;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.signal.ExternalSignal;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.registry.ExecutorRegistry;
import tech.kayys.gamelan.registry.ExecutorSelectionRequest;
import tech.kayys.gamelan.registry.ExecutorSelectionReport;

class WorkflowOrchestratorTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("start");

    private Vertx vertx;
    private RecordingWorkflowRunRepository runRepository;
    private RecordingWorkflowRunManager runManager;
    private RecordingTaskDispatcher dispatcher;
    private WorkflowOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        runRepository = new RecordingWorkflowRunRepository();
        runManager = new RecordingWorkflowRunManager(runRepository);
        dispatcher = new RecordingTaskDispatcher();
        orchestrator = new WorkflowOrchestrator();
        orchestrator.eventBus = vertx.eventBus();
        orchestrator.runManager = runManager;
        orchestrator.runRepository = runRepository;
        orchestrator.executionEngine = new WorkflowExecutionEngine();
        orchestrator.executorRegistry = new FixedExecutorRegistry(executor());
        orchestrator.taskDispatcher = dispatcher;
        orchestrator.noExecutorPolicy = "fail";
    }

    @AfterEach
    void tearDown() {
        vertx.close().await().indefinitely();
    }

    @Test
    void handleRunUpdate_ignoresBlankRunId() {
        orchestrator.handleRunUpdate(" ").await().indefinitely();

        assertEquals(0, runRepository.findCount.get());
        assertEquals(0, dispatcher.dispatchCount.get());
    }

    @Test
    void handleRunUpdate_drivesTrimmedRunId() {
        WorkflowDefinition definition = workflow(node(false));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.handleRunUpdate("  " + run.getId().value() + "  ").await().indefinitely();

        assertEquals(1, runRepository.findCount.get());
        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(NODE_ID).getStatus());
    }

    @Test
    void handleRunUpdate_drivesTenantAwarePayloadWithTenantScopedLookup() {
        WorkflowDefinition definition = workflow(node(false));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        JsonObject payload = JsonObject.mapFrom(WorkflowRunUpdateEvent.of(
                run.getId(),
                TENANT,
                "test"));

        orchestrator.handleRunUpdate(payload).await().indefinitely();

        assertEquals(0, runRepository.findCount.get());
        assertEquals(1, runRepository.tenantFindCount.get());
        assertEquals(TENANT, runRepository.lastFindTenant);
        assertEquals(1, dispatcher.dispatchCount.get());
    }

    @Test
    void handleNodeResult_ignoresMalformedPayload() {
        orchestrator.handleNodeResult(new JsonObject().put("runId", "")).await().indefinitely();

        assertEquals(0, runManager.resultCount.get());
    }

    @Test
    void handleNodeResult_routesDefaultResultPayload() {
        WorkflowRunId runId = WorkflowRunId.generate();
        JsonObject payload = new JsonObject()
                .put("runId", runId.value())
                .put("nodeId", NODE_ID.value())
                .put("attempt", 1)
                .put("status", NodeExecutionStatus.COMPLETED.name())
                .put("output", new JsonObject().put("summary", "ok"));

        orchestrator.handleNodeResult(payload).await().indefinitely();

        assertEquals(1, runManager.resultCount.get());
        assertEquals(runId, runManager.lastResultRunId);
        assertEquals(NODE_ID, runManager.lastResult.nodeId());
        assertEquals(NodeExecutionStatus.COMPLETED, runManager.lastResult.status());
        assertEquals("ok", runManager.lastResult.output().get("summary"));
    }

    @Test
    void handleNodeResult_routesTenantAwarePayloadWhenTenantIdIsPresent() {
        WorkflowRunId runId = WorkflowRunId.generate();
        JsonObject payload = new JsonObject()
                .put("runId", runId.value())
                .put("tenantId", TENANT.value())
                .put("nodeId", NODE_ID.value())
                .put("attempt", 1)
                .put("status", NodeExecutionStatus.COMPLETED.name())
                .put("output", new JsonObject().put("summary", "ok"));

        orchestrator.handleNodeResult(payload).await().indefinitely();

        assertEquals(1, runManager.resultCount.get());
        assertEquals(runId, runManager.lastResultRunId);
        assertEquals(TENANT, runManager.lastResultTenantId);
        assertEquals(NODE_ID, runManager.lastResult.nodeId());
    }

    @Test
    void drive_claimsNodeBeforeDispatchSoRepeatedDriveDoesNotRedispatchPendingWork() {
        WorkflowDefinition definition = workflow(node(false));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();
        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals(1, runManager.tokenCount.get());
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(NODE_ID).getStatus());
        assertTrue(run.getPendingNodes().isEmpty());
        assertEquals(1, dispatcher.lastTask.attempt());
        assertEquals("local", dispatcher.lastTask.context().get(NodeExecutionTask.NODE_TYPE_KEY));
    }

    @Test
    void drive_coalescesConcurrentRequestsForSameRunIntoOneFollowUpCycle() throws Exception {
        WorkflowDefinition definition = workflow(node(false));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        CountDownLatch dispatchStarted = new CountDownLatch(1);
        CompletableFuture<Void> dispatchGate = new CompletableFuture<>();
        dispatcher.blockDispatch(dispatchStarted, dispatchGate);

        CompletableFuture<Void> firstDrive = orchestrator.drive(run.getId())
                .subscribeAsCompletionStage()
                .toCompletableFuture();
        assertTrue(dispatchStarted.await(2, TimeUnit.SECONDS));

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, runRepository.findCount.get());
        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals(1, runManager.tokenCount.get());

        dispatchGate.complete(null);
        firstDrive.get(2, TimeUnit.SECONDS);

        assertEquals(2, runRepository.findCount.get());
        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals(1, runManager.tokenCount.get());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(NODE_ID).getStatus());
    }

    @Test
    void drive_limitsReadyNodeDispatchPerCycleAndSchedulesFollowUp() throws Exception {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second");
        NodeDefinition third = node("third");
        WorkflowDefinition definition = workflow(List.of(first, second, third));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        orchestrator.maxReadyNodesPerCycle = 2;

        CountDownLatch followUpPublished = new CountDownLatch(1);
        vertx.eventBus().<Object>consumer(WorkflowOrchestrator.RUN_UPDATED_ADDRESS)
                .handler(message -> {
                    Object body = message.body();
                    String eventRunId = body instanceof JsonObject json
                            ? json.getString("runId")
                            : String.valueOf(body);
                    if (run.getId().value().equals(eventRunId)) {
                        followUpPublished.countDown();
                    }
                });

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(2, dispatcher.dispatchCount.get());
        assertEquals(2, runManager.tokenCount.get());
        assertEquals(2, runRepository.updateCount.get());
        assertEquals(java.util.Set.of(first.id(), second.id()), java.util.Set.copyOf(dispatcher.dispatchedNodes));
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(first.id()).getStatus());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(second.id()).getStatus());
        assertEquals(NodeExecutionStatus.PENDING, run.getNodeExecution(third.id()).getStatus());
        assertTrue(followUpPublished.await(2, TimeUnit.SECONDS));
    }

    @Test
    void drive_usesInjectedWakeupPublisherForFollowUpWithoutEventBusCoupling() {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second");
        NodeDefinition third = node("third");
        WorkflowDefinition definition = workflow(List.of(first, second, third));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        orchestrator.maxReadyNodesPerCycle = 2;
        orchestrator.eventBus = null;
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();
        orchestrator.wakeupPublisher = wakeupPublisher;

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(2, dispatcher.dispatchCount.get());
        assertEquals(1, wakeupPublisher.events.size());
        WorkflowRunUpdateEvent event = wakeupPublisher.events.getFirst();
        assertEquals(run.getId().value(), event.runId());
        assertEquals(TENANT.value(), event.tenantId());
        assertEquals("ready-batch-remaining", event.reason());
    }

    @Test
    void drive_limitsConcurrentDispatchesWithinReadyNodeBatch() throws Exception {
        WorkflowDefinition definition = workflow(List.of(
                node("first"),
                node("second"),
                node("third"),
                node("fourth"),
                node("fifth")));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        orchestrator.maxReadyNodesPerCycle = 10;
        orchestrator.maxConcurrentDispatches = 2;

        CountDownLatch firstChunkStarted = new CountDownLatch(2);
        CompletableFuture<Void> dispatchGate = new CompletableFuture<>();
        dispatcher.blockDispatch(firstChunkStarted, dispatchGate);

        CompletableFuture<Void> drive = orchestrator.drive(run.getId())
                .subscribeAsCompletionStage()
                .toCompletableFuture();

        assertTrue(firstChunkStarted.await(2, TimeUnit.SECONDS));
        assertEquals(2, dispatcher.dispatchCount.get());
        assertEquals(2, dispatcher.maxActiveDispatches.get());

        dispatchGate.complete(null);
        drive.get(2, TimeUnit.SECONDS);

        assertEquals(5, dispatcher.dispatchCount.get());
        assertEquals(2, dispatcher.maxActiveDispatches.get());
    }

    @Test
    void drive_normalizesReadyNodesBeforeExecutorSelectionAndDispatch() {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second");
        WorkflowDefinition definition = workflow(List.of(first, second));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        orchestrator.executionEngine = new FixedPlanExecutionEngine(new ExecutionPlan(
                List.of(first.id(), first.id(), NodeId.of("missing"), second.id()),
                false,
                false,
                Map.of()));
        FixedExecutorRegistry executorRegistry = new FixedExecutorRegistry(executor());
        orchestrator.executorRegistry = executorRegistry;

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(2, executorRegistry.selectionCount.get());
        assertEquals(2, dispatcher.dispatchCount.get());
        assertEquals(2, runManager.tokenCount.get());
        assertEquals(List.of(first.id(), second.id()), dispatcher.dispatchedNodes);
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(first.id()).getStatus());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(second.id()).getStatus());
    }

    @Test
    void drive_skipsExecutorSelectionWhenPlannerReturnsAlreadyClaimedNode() {
        WorkflowDefinition definition = workflow(node(false));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        run.startNode(NODE_ID, 1);
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        orchestrator.executionEngine = new FixedPlanExecutionEngine(new ExecutionPlan(
                List.of(NODE_ID),
                false,
                false,
                Map.of()));
        FixedExecutorRegistry executorRegistry = new FixedExecutorRegistry(executor());
        orchestrator.executorRegistry = executorRegistry;

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(0, executorRegistry.selectionCount.get());
        assertEquals(0, dispatcher.dispatchCount.get());
        assertEquals(0, runManager.tokenCount.get());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(NODE_ID).getStatus());
    }

    @Test
    void drive_failsRunWhenPlannerReportsStuckWorkflow() {
        NodeDefinition start = node(false);
        NodeDefinition blocked = new NodeDefinition(
                NodeId.of("blocked"),
                "blocked",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(start.id()),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                true);
        WorkflowDefinition definition = workflow(List.of(start, blocked));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        run.startNode(start.id(), 1);
        run.completeNode(start.id(), 1, Map.of());
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        orchestrator.executionEngine = new FixedPlanExecutionEngine(new ExecutionPlan(
                List.of(),
                false,
                true,
                Map.of()));

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, runManager.failCount.get());
        assertEquals(run.getId(), runManager.lastFailedRunId);
        assertEquals(TENANT, runManager.lastFailedTenantId);
        assertEquals("WORKFLOW_STUCK", runManager.lastFailure.code());
        assertEquals(List.of("blocked"), runManager.lastFailure.context().get("unresolvedNodes"));
        assertEquals(0, dispatcher.dispatchCount.get());
    }

    @Test
    void drive_failsRunWhenDefinitionIsInvalidForPlanning() throws Exception {
        NodeDefinition blocked = new NodeDefinition(
                NodeId.of("blocked"),
                "blocked",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(NodeId.of("missing")),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                true);
        WorkflowDefinition definition = workflow(List.of(blocked));
        WorkflowRun run = runningRunWithoutNodeExecutions();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, runManager.failCount.get());
        assertEquals(run.getId(), runManager.lastFailedRunId);
        assertEquals(TENANT, runManager.lastFailedTenantId);
        assertEquals("WORKFLOW_DEFINITION_INVALID", runManager.lastFailure.code());
        assertTrue(runManager.lastFailure.message().contains("cannot be planned"));
        assertTrue(runManager.lastFailure.context().get("validationErrors").toString()
                .contains("references unknown dependency"));
        assertEquals(0, dispatcher.dispatchCount.get());
    }

    @Test
    void drive_dispatchesNodeConfigWorkflowVariablesAndRunMetadataInTaskContext() {
        WorkflowDefinition definition = workflow(node(false, Map.of(
                "__node_type__", "script",
                "language", "javascript",
                "script", "return context.topic;"),
                Duration.ofSeconds(45)));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of(
                "topic", "orders",
                "priority", 7));
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        NodeExecutionTask task = dispatcher.lastTask;
        assertEquals("script", task.context().get(NodeExecutionTask.NODE_TYPE_KEY));
        assertEquals("javascript", task.context().get("language"));
        assertEquals("javascript", task.nodeConfiguration().get("language"));
        assertEquals("orders", task.workflowVariables().get("topic"));
        assertEquals(7, task.workflowVariables().get("priority"));
        assertEquals(run.getId().value(), task.context().get(NodeExecutionTask.RUN_ID_KEY));
        assertEquals("wf-test", task.context().get(NodeExecutionTask.WORKFLOW_DEFINITION_ID_KEY));
        assertEquals(TENANT.value(), task.context().get(NodeExecutionTask.TENANT_ID_KEY));
        assertEquals(TENANT, task.token().tenantId());
        assertEquals(NODE_ID.value(), task.context().get(NodeExecutionTask.NODE_ID_KEY));
        assertEquals("start", task.context().get(NodeExecutionTask.NODE_NAME_KEY));
        assertEquals(1, task.context().get(NodeExecutionTask.ATTEMPT_KEY));
        assertEquals(45L, task.context().get(NodeExecutionTask.TIMEOUT_SECONDS_KEY));
        assertEquals(
                "orders",
                ((Map<?, ?>) task.context().get(NodeExecutionTask.LEGACY_CONTEXT_KEY)).get("topic"));
    }

    @Test
    void drive_preservesExplicitNodeContextWhenEnrichingTaskContext() {
        Map<String, Object> explicitContext = Map.of("prompt", "Summarize orders");
        WorkflowDefinition definition = workflow(node(false, Map.of(
                "__node_type__", "agent",
                NodeExecutionTask.LEGACY_CONTEXT_KEY, explicitContext)));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("topic", "orders"));
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        NodeExecutionTask task = dispatcher.lastTask;
        assertEquals(explicitContext, task.context().get(NodeExecutionTask.LEGACY_CONTEXT_KEY));
        assertEquals("orders", task.workflowVariables().get("topic"));
    }

    @Test
    void drive_preservesTypedCollaborationContextForDistributedAgentsAndHumans() {
        WorkflowDefinition definition = workflow(node(false, Map.of(
                "__node_type__", "agent",
                NodeExecutionTask.COLLABORATION_CONTEXT_KEY, Map.of(
                        "collaborationId", "incident-123",
                        "participants", List.of(
                                Map.of(
                                        "id", "agent:planner",
                                        "kind", "agent",
                                        "runtime", "distributed",
                                        "isolation", "sandbox",
                                        "roles", List.of("planner")),
                                Map.of(
                                        "id", "human:approver",
                                        "kind", "human",
                                        "runtime", "external",
                                        "roles", List.of("approver")))))));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("topic", "orders"));
        run.start();
        runRepository.run = run;
        orchestrator.executorRegistry = new FixedExecutorRegistry(distributedSandboxExecutor());
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        CollaborationContext collaboration = dispatcher.lastTask.collaborationContext().orElseThrow();
        assertEquals("incident-123", collaboration.collaborationId());
        assertEquals(2, collaboration.participants().size());
        assertEquals(ParticipantKind.AGENT, collaboration.participants().getFirst().kind());
        assertEquals(ParticipantRuntime.DISTRIBUTED, collaboration.participants().getFirst().runtime());
        assertEquals(ParticipantIsolation.SANDBOX, collaboration.participants().getFirst().isolation());
        assertEquals(1, collaboration.participantsByKind(ParticipantKind.HUMAN).size());
        assertTrue(collaboration.hasSandboxedParticipant());
    }

    @Test
    void drive_selectsExecutorCompatibleWithCollaborationPlacement() {
        WorkflowDefinition definition = workflow(node(false, Map.of(
                "__node_type__", "agent",
                NodeExecutionTask.COLLABORATION_CONTEXT_KEY, Map.of(
                        "participants", List.of(Map.of(
                                "id", "agent:planner",
                                "kind", "agent",
                                "runtime", "distributed",
                                "isolation", "sandbox",
                                "roles", List.of("planner")))))));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("topic", "orders"));
        run.start();
        runRepository.run = run;
        orchestrator.executorRegistry = new FixedExecutorRegistry(List.of(
                executor(),
                distributedSandboxExecutor()));
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals("distributed-sandbox-executor", dispatcher.lastExecutor.executorId());
    }

    @Test
    void drive_passesNodeExecutorSelectionStrategyToRegistry() {
        WorkflowDefinition definition = workflow(node(false, Map.of(
                NodeExecutionTask.EXECUTOR_SELECTION_KEY, Map.of(
                        ExecutorSelectionRequest.STRATEGY_KEY, "weighted",
                        ExecutorSelectionPolicy.CONTEXT_REQUIRED_CAPABILITIES_KEY, List.of("coding", "sandbox"),
                        "pool", "agentic-local"))));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("topic", "orders"));
        run.start();
        runRepository.run = run;
        FixedExecutorRegistry registry = new FixedExecutorRegistry(executor());
        orchestrator.executorRegistry = registry;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals("weighted", registry.lastRequest.selectionStrategy());
        assertEquals(java.util.Set.of("coding", "sandbox"), registry.lastRequest.requiredCapabilities());
        assertEquals("agentic-local", registry.lastRequest.selectionContext().get("pool"));
    }

    @Test
    void drive_marksNodeFailedWhenNoExecutorMatchesCollaborationPlacement() {
        WorkflowDefinition definition = workflow(node(true, Map.of(
                "__node_type__", "agent",
                NodeExecutionTask.COLLABORATION_CONTEXT_KEY, Map.of(
                        "participants", List.of(Map.of(
                                "id", "agent:planner",
                                "kind", "agent",
                                "runtime", "distributed",
                                "isolation", "sandbox"))))));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("topic", "orders"));
        run.start();
        runRepository.run = run;
        orchestrator.executorRegistry = new FixedExecutorRegistry(executor());
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(0, dispatcher.dispatchCount.get());
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(NODE_ID).getStatus());
        assertEquals("NO_EXECUTOR_AVAILABLE", run.getNodeExecution(NODE_ID).getLastError().code());
        assertTrue(run.getNodeExecution(NODE_ID).getLastError().context().containsKey("placement"));
        assertEquals(PLACEMENT_MISMATCH,
                run.getNodeExecution(NODE_ID).getLastError().context().get("selectionReason"));
        assertEquals(false,
                run.getNodeExecution(NODE_ID).getLastError().context().get("permanentSelectionFailure"));
        Map<?, ?> selection = (Map<?, ?>) run.getNodeExecution(NODE_ID).getLastError().context().get("selection");
        assertEquals(1, selection.get("totalExecutors"));
        assertEquals(0, selection.get("candidateExecutors"));
        assertEquals(PLACEMENT_MISMATCH, selection.get("primaryRejectionReason"));
        assertEquals(1, ((Map<?, ?>) selection.get("rejectionCounts")).get(PLACEMENT_MISMATCH));
    }

    @Test
    void drive_marksNodeFailedWhenDispatchFailsAfterReservation() {
        WorkflowDefinition definition = workflow(node(true));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        dispatcher.failure = new IllegalStateException("broker unavailable");
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals(2, runRepository.updateCount.get());
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(NODE_ID).getStatus());
    }

    @Test
    void drive_marksNodeFailedWhenNoExecutorAvailableByDefault() {
        WorkflowDefinition definition = workflow(node(true));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.executorRegistry = new EmptyExecutorRegistry();
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(0, dispatcher.dispatchCount.get());
        assertEquals(0, runManager.tokenCount.get());
        assertEquals(2, runRepository.updateCount.get());
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(NODE_ID).getStatus());
        assertEquals("NO_EXECUTOR_AVAILABLE", run.getNodeExecution(NODE_ID).getLastError().code());
        assertEquals(NO_EXECUTOR, run.getNodeExecution(NODE_ID).getLastError().context().get("selectionReason"));
        assertEquals(TaskAdmissionAction.REJECT.metricName(),
                run.getNodeExecution(NODE_ID).getLastError().context().get("admissionAction"));
        assertEquals(NO_EXECUTOR, run.getNodeExecution(NODE_ID).getLastError().context().get("admissionReason"));
        assertEquals("fail", run.getNodeExecution(NODE_ID).getLastError().context().get("admissionPolicy"));
        Map<?, ?> selection = (Map<?, ?>) run.getNodeExecution(NODE_ID).getLastError().context().get("selection");
        assertEquals(0, selection.get("totalExecutors"));
        assertEquals(0, selection.get("candidateExecutors"));
        assertEquals(NO_EXECUTOR, selection.get("primaryRejectionReason"));
    }

    @Test
    void drive_recordsDispatchAdmissionDecisionWhenExecutorSelected() {
        WorkflowDefinition definition = workflow(node(false));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskAdmissionController admissionController = new TaskAdmissionController();
        admissionController.meterRegistry = meterRegistry;
        orchestrator.admissionController = admissionController;

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(1, dispatcher.dispatchCount.get());
        assertEquals(1.0, admissionCounter(meterRegistry, "dispatch", "executor-selected", "fail"));
    }

    @Test
    void drive_keepsNodePendingWhenNoExecutorPolicyIsWait() {
        WorkflowDefinition definition = workflow(node(true));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        runRepository.run = run;
        orchestrator.executorRegistry = new EmptyExecutorRegistry();
        orchestrator.definitionRegistry = new FixedDefinitionRegistry(definition);
        orchestrator.noExecutorPolicy = "wait";
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TaskAdmissionController admissionController = new TaskAdmissionController();
        admissionController.meterRegistry = meterRegistry;
        orchestrator.admissionController = admissionController;

        orchestrator.drive(run.getId()).await().indefinitely();

        assertEquals(0, dispatcher.dispatchCount.get());
        assertEquals(0, runManager.tokenCount.get());
        assertEquals(0, runRepository.updateCount.get());
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(NodeExecutionStatus.PENDING, run.getNodeExecution(NODE_ID).getStatus());
        assertEquals(List.of(NODE_ID), run.getPendingNodes());
        assertEquals(1.0, admissionCounter(meterRegistry, "wait_for_executor", NO_EXECUTOR, "wait"));
    }

    @Test
    void admissionController_classifiesCapacitySaturationAsDeferrableForWaitPolicy() {
        NodeDefinition node = node(true);
        ExecutorSelectionReport report = new ExecutorSelectionReport(
                ExecutorSelectionRequest.forNodeType(
                        node.id(),
                        node.executorType(),
                        ExecutorPlacementRequirements.none()),
                Optional.empty(),
                1,
                0,
                0,
                1,
                1,
                1,
                0,
                Map.of(CAPACITY_SATURATED, 1),
                Map.of("registry", "capacity-test"));

        TaskAdmissionDecision decision = new TaskAdmissionController().noExecutor(node, report, "wait");

        assertEquals(TaskAdmissionAction.DEFER_CAPACITY, decision.action());
        assertEquals(CAPACITY_SATURATED, decision.reason());
    }

    private static WorkflowDefinition workflow(NodeDefinition node) {
        return workflow(List.of(node));
    }

    private static WorkflowDefinition workflow(List<NodeDefinition> nodes) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-test"),
                TENANT,
                "test",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                nodes,
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static void forceStatus(WorkflowRun run, RunStatus status) throws Exception {
        var statusField = WorkflowRun.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(run, status);
    }

    private static WorkflowRun runningRunWithoutNodeExecutions() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(node(false)), Map.of());
        forceStatus(run, RunStatus.RUNNING);
        return run;
    }

    private static NodeDefinition node(boolean critical) {
        return node(critical, Map.of());
    }

    private static NodeDefinition node(boolean critical, Map<String, Object> configuration) {
        return node(critical, configuration, Duration.ZERO);
    }

    private static NodeDefinition node(boolean critical, Map<String, Object> configuration, Duration timeout) {
        return new NodeDefinition(
                NODE_ID,
                "start",
                NodeType.TASK,
                "local",
                configuration,
                List.of(),
                List.of(),
                RetryPolicy.none(),
                timeout,
                critical);
    }

    private static NodeDefinition node(String id) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static ExecutorInfo executor() {
        return new ExecutorInfo(
                "local-executor",
                "local",
                CommunicationType.LOCAL,
                "local",
                Duration.ofSeconds(30),
                Map.of());
    }

    private static ExecutorInfo distributedSandboxExecutor() {
        return new ExecutorInfo(
                "distributed-sandbox-executor",
                "local",
                CommunicationType.GRPC,
                "grpc://sandbox",
                Duration.ofSeconds(30),
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "remote,distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox"));
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not implemented for this test");
    }

    private static final class FixedDefinitionRegistry extends WorkflowDefinitionRegistry {
        private final WorkflowDefinition definition;

        private FixedDefinitionRegistry(WorkflowDefinition definition) {
            this.definition = definition;
        }

        @Override
        public Uni<WorkflowDefinition> getDefinition(WorkflowDefinitionId id, TenantId tenantId) {
            return Uni.createFrom().item(definition);
        }
    }

    private static final class FixedPlanExecutionEngine extends WorkflowExecutionEngine {
        private final ExecutionPlan plan;

        private FixedPlanExecutionEngine(ExecutionPlan plan) {
            this.plan = plan;
        }

        @Override
        public Uni<ExecutionPlan> planNextExecution(WorkflowRun run, WorkflowDefinition definition) {
            return Uni.createFrom().item(plan);
        }
    }

    private static final class FixedExecutorRegistry extends ExecutorRegistry {
        private final List<ExecutorInfo> executors;
        private final AtomicInteger selectionCount = new AtomicInteger();
        private ExecutorSelectionRequest lastRequest;

        private FixedExecutorRegistry(ExecutorInfo executor) {
            this(List.of(executor));
        }

        private FixedExecutorRegistry(List<ExecutorInfo> executors) {
            this.executors = List.copyOf(executors);
        }

        @Override
        public Uni<List<ExecutorInfo>> getExecutorsByType(String executorType) {
            return Uni.createFrom().item(executors.stream()
                    .filter(executor -> executor.executorType().equals(executorType))
                    .toList());
        }

        @Override
        public Uni<List<ExecutorInfo>> getHealthyExecutorsByType(
                String executorType,
                ExecutorPlacementRequirements placement) {
            return Uni.createFrom().item(executors.stream()
                    .filter(executor -> executor.executorType().equals(executorType))
                    .filter(executor -> placement == null || placement.matches(executor))
                    .toList());
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorForNodeByType(
                NodeId nodeId,
                String executorType,
                ExecutorPlacementRequirements placement) {
            return Uni.createFrom().item(executors.stream()
                    .filter(executor -> executor.executorType().equals(executorType))
                    .filter(executor -> placement == null || placement.matches(executor))
                    .findFirst());
        }

        @Override
        public Uni<Optional<ExecutorInfo>> selectExecutor(ExecutorSelectionRequest request) {
            return selectExecutorWithDiagnostics(request)
                    .map(ExecutorSelectionReport::selectedExecutor);
        }

        @Override
        public Uni<ExecutorSelectionReport> selectExecutorWithDiagnostics(ExecutorSelectionRequest request) {
            selectionCount.incrementAndGet();
            lastRequest = request;
            int typeCompatible = 0;
            int placementCompatible = 0;
            Map<String, Integer> rejections = new java.util.LinkedHashMap<>();
            Optional<ExecutorInfo> selected = Optional.empty();

            for (ExecutorInfo executor : executors) {
                if (request.hasExecutorType() && !executor.executorType().equals(request.executorType())) {
                    increment(rejections, EXECUTOR_TYPE_MISMATCH);
                    continue;
                }
                typeCompatible++;

                if (request.placement() != null && !request.placement().matches(executor)) {
                    increment(rejections, PLACEMENT_MISMATCH);
                    continue;
                }
                placementCompatible++;
                if (selected.isEmpty()) {
                    selected = Optional.of(executor);
                }
            }

            return Uni.createFrom().item(new ExecutorSelectionReport(
                    request,
                    selected,
                    executors.size(),
                    0,
                    0,
                    typeCompatible,
                    typeCompatible,
                    placementCompatible,
                    placementCompatible,
                    rejections,
                    Map.of("registry", "fixed-test")));
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorForNode(NodeId nodeId) {
            return Uni.createFrom().item(executors.stream().findFirst());
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorForNode(
                NodeId nodeId,
                ExecutorPlacementRequirements placement) {
            return Uni.createFrom().item(executors.stream()
                    .filter(executor -> placement == null || placement.matches(executor))
                    .findFirst());
        }
    }

    private static final class EmptyExecutorRegistry extends ExecutorRegistry {
        @Override
        public Uni<List<ExecutorInfo>> getExecutorsByType(String executorType) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<ExecutorInfo>> getHealthyExecutorsByType(
                String executorType,
                ExecutorPlacementRequirements placement) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorForNodeByType(
                NodeId nodeId,
                String executorType,
                ExecutorPlacementRequirements placement) {
            return Uni.createFrom().item(Optional.empty());
        }

        @Override
        public Uni<Optional<ExecutorInfo>> selectExecutor(ExecutorSelectionRequest request) {
            return Uni.createFrom().item(Optional.empty());
        }

        @Override
        public Uni<ExecutorSelectionReport> selectExecutorWithDiagnostics(ExecutorSelectionRequest request) {
            return Uni.createFrom().item(new ExecutorSelectionReport(
                    request,
                    Optional.empty(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Map.of(),
                    Map.of("registry", "empty-test")));
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorForNode(NodeId nodeId) {
            return Uni.createFrom().item(Optional.empty());
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorForNode(
                NodeId nodeId,
                ExecutorPlacementRequirements placement) {
            return Uni.createFrom().item(Optional.empty());
        }
    }

    private static final class RecordingTaskDispatcher extends TaskDispatcherAggregator {
        private final AtomicInteger dispatchCount = new AtomicInteger();
        private final AtomicInteger activeDispatches = new AtomicInteger();
        private final AtomicInteger maxActiveDispatches = new AtomicInteger();
        private final List<NodeId> dispatchedNodes = new java.util.concurrent.CopyOnWriteArrayList<>();
        private NodeExecutionTask lastTask;
        private RuntimeException failure;
        private CountDownLatch dispatchStarted;
        private CompletableFuture<Void> dispatchGate;

        void blockDispatch(CountDownLatch dispatchStarted, CompletableFuture<Void> dispatchGate) {
            this.dispatchStarted = dispatchStarted;
            this.dispatchGate = dispatchGate;
        }

        @Override
        public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
            int active = activeDispatches.incrementAndGet();
            maxActiveDispatches.accumulateAndGet(active, Math::max);
            dispatchCount.incrementAndGet();
            dispatchedNodes.add(task.nodeId());
            lastTask = task;
            lastExecutor = executor;
            if (dispatchStarted != null) {
                dispatchStarted.countDown();
            }
            Uni<Void> dispatch;
            if (failure != null) {
                dispatch = Uni.createFrom().failure(failure);
            } else if (dispatchGate != null) {
                dispatch = Uni.createFrom().completionStage(dispatchGate);
            } else {
                dispatch = Uni.createFrom().voidItem();
            }
            return dispatch.onItemOrFailure().invoke((ignored, error) -> activeDispatches.decrementAndGet());
        }

        private ExecutorInfo lastExecutor;
    }

    private static void increment(Map<String, Integer> counts, String reason) {
        counts.merge(reason, 1, Integer::sum);
    }

    private static double admissionCounter(
            SimpleMeterRegistry meterRegistry,
            String action,
            String reason,
            String policy) {
        return meterRegistry.get("gamelan.orchestrator.admission.decisions")
                .tags("action", action, "reason", reason, "policy", policy)
                .counter()
                .count();
    }

    private static final class RecordingWakeupPublisher implements WorkflowRunWakeupPublisher {
        private final List<WorkflowRunUpdateEvent> events = new java.util.ArrayList<>();

        @Override
        public Uni<Void> publish(WorkflowRunUpdateEvent event) {
            events.add(event);
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingWorkflowRunRepository implements WorkflowRunRepository {
        private WorkflowRun run;
        private final AtomicInteger updateCount = new AtomicInteger();
        private final AtomicInteger findCount = new AtomicInteger();
        private final AtomicInteger tenantFindCount = new AtomicInteger();
        private TenantId lastFindTenant;

        @Override
        public Uni<WorkflowRun> persist(WorkflowRun run) {
            this.run = run;
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> update(WorkflowRun run) {
            this.run = run;
            updateCount.incrementAndGet();
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id) {
            findCount.incrementAndGet();
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
            tenantFindCount.incrementAndGet();
            lastFindTenant = tenantId;
            return Uni.createFrom().item(run);
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
            return action.apply(run);
        }

        @Override
        public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().item(run.createSnapshot());
        }

        @Override
        public Uni<List<WorkflowRun>> query(
                TenantId tenantId,
                WorkflowDefinitionId definitionId,
                RunStatus status,
                int page,
                int size) {
            return Uni.createFrom().item(List.of(run));
        }

        @Override
        public Uni<Long> countActiveRuns(TenantId tenantId) {
            return Uni.createFrom().item(run.getStatus().isActive() ? 1L : 0L);
        }

        @Override
        public Uni<Void> storeToken(ExecutionToken token) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateToken(ExecutionToken token) {
            return Uni.createFrom().item(true);
        }

        @Override
        public Uni<Void> storeCallback(CallbackRegistration callback) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
            return Uni.createFrom().item(true);
        }

        @Override
        public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingWorkflowRunManager implements WorkflowRunManager {
        private final RecordingWorkflowRunRepository runRepository;
        private final AtomicInteger tokenCount = new AtomicInteger();
        private final AtomicInteger resultCount = new AtomicInteger();
        private final AtomicInteger failCount = new AtomicInteger();
        private WorkflowRunId lastResultRunId;
        private TenantId lastResultTenantId;
        private NodeExecutionResult lastResult;
        private WorkflowRunId lastFailedRunId;
        private TenantId lastFailedTenantId;
        private ErrorInfo lastFailure;

        private RecordingWorkflowRunManager(RecordingWorkflowRunRepository runRepository) {
            this.runRepository = runRepository;
        }

        @Override
        public Uni<WorkflowRun> createRun(CreateRunRequest request) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<WorkflowRun> startRun(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<WorkflowRun> suspendRun(
                WorkflowRunId runId,
                TenantId tenantId,
                String reason,
                NodeId waitingOnNodeId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<WorkflowRun> resumeRun(
                WorkflowRunId runId,
                TenantId tenantId,
                Map<String, Object> resumeData,
                String humanTaskId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<Void> cancelRun(WorkflowRunId runId, TenantId tenantId, String reason) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<WorkflowRun> completeRun(
                WorkflowRunId runId,
                TenantId tenantId,
                Map<String, Object> outputs) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<WorkflowRun> failRun(WorkflowRunId runId, TenantId tenantId, ErrorInfo error) {
            failCount.incrementAndGet();
            lastFailedRunId = runId;
            lastFailedTenantId = tenantId;
            lastFailure = error;
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<Void> completeCompensation(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<Void> failCompensation(WorkflowRunId runId, TenantId tenantId, ErrorInfo error) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<NodeDispatchReservation> reserveNodeForDispatch(
                WorkflowRunId runId,
                TenantId tenantId,
                NodeId nodeId) {
            return runRepository.withLock(runId, tenantId, run -> {
                if (run.getStatus() != RunStatus.RUNNING) {
                    return Uni.createFrom().item(NodeDispatchReservation.skipped(
                            runId,
                            tenantId,
                            nodeId,
                            "run-not-running"));
                }
                return Uni.createFrom().item(run.reserveNodeForDispatch(nodeId))
                        .flatMap(reserved -> reserved.isEmpty()
                                ? Uni.createFrom().item(NodeDispatchReservation.skipped(
                                        runId,
                                        tenantId,
                                        nodeId,
                                        "node-not-ready"))
                                : runRepository.update(run)
                                        .replaceWith(NodeDispatchReservation.reserved(
                                                runId,
                                                tenantId,
                                                nodeId,
                                                reserved.get().getAttempt())));
            });
        }

        @Override
        public Uni<Void> failNodeExecution(
                WorkflowRunId runId,
                TenantId tenantId,
                NodeId nodeId,
                int attempt,
                ErrorInfo error,
                String wakeupReason) {
            return runRepository.withLock(runId, tenantId, run -> {
                var execution = run.getAllNodeExecutions().get(nodeId);
                if (run.getStatus().isTerminal()
                        || execution == null
                        || execution.getAttempt() != attempt
                        || execution.getStatus().isTerminal()) {
                    return Uni.createFrom().voidItem();
                }
                run.failNode(nodeId, attempt, error != null ? error : new ErrorInfo(
                        "NODE_DISPATCH_FAILED",
                        "Node dispatch failed",
                        "",
                        Map.of()));
                return runRepository.update(run).replaceWithVoid();
            });
        }

        @Override
        public Uni<Void> handleNodeResult(WorkflowRunId runId, NodeExecutionResult result) {
            resultCount.incrementAndGet();
            lastResultRunId = runId;
            lastResultTenantId = null;
            lastResult = result;
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> handleNodeResult(WorkflowRunId runId, TenantId tenantId, NodeExecutionResult result) {
            resultCount.incrementAndGet();
            lastResultRunId = runId;
            lastResultTenantId = tenantId;
            lastResult = result;
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> signal(WorkflowRunId runId, Signal signal) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<WorkflowRun> getRun(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<WorkflowRunSnapshot> getSnapshot(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<ExecutionHistory> getExecutionHistory(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<List<WorkflowRun>> queryRuns(
                TenantId tenantId,
                WorkflowDefinitionId definitionId,
                RunStatus status,
                int page,
                int size) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<Long> getActiveRunsCount(TenantId tenantId) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<ValidationResult> validateTransition(WorkflowRunId runId, RunStatus targetStatus) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<ExecutionToken> createExecutionToken(WorkflowRunId runId, NodeId nodeId, int attempt) {
            return createExecutionToken(runId, null, nodeId, attempt);
        }

        @Override
        public Uni<ExecutionToken> createExecutionToken(
                WorkflowRunId runId,
                TenantId tenantId,
                NodeId nodeId,
                int attempt) {
            tokenCount.incrementAndGet();
            return Uni.createFrom().item(new ExecutionToken(
                    "token-" + attempt,
                    runId,
                    tenantId,
                    nodeId,
                    attempt,
                    Instant.now().plusSeconds(60)));
        }

        @Override
        public Uni<Void> onNodeExecutionCompleted(NodeExecutionResult result, String executorSignature) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<Void> onExternalSignal(WorkflowRunId runId, ExternalSignal signal, String callbackToken) {
            return Uni.createFrom().failure(unsupported());
        }

        @Override
        public Uni<CallbackRegistration> registerCallback(WorkflowRunId runId, NodeId nodeId, CallbackConfig config) {
            return Uni.createFrom().failure(unsupported());
        }
    }
}
