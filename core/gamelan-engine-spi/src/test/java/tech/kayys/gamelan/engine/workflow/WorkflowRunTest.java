package tech.kayys.gamelan.engine.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.NodeScheduledEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;

class WorkflowRunTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    @Test
    void create_appliesInputDefaultsToContextWithoutMutatingCallerInputs() {
        Map<String, Object> callerInputs = new HashMap<>();
        WorkflowDefinition definition = workflow(
                "defaults",
                List.of(node("start")),
                Map.of("topic", new InputDefinition("topic", "string", true, "orders", null)),
                CompensationPolicy.disabled());

        WorkflowRun run = WorkflowRun.create(TENANT, definition, callerInputs);

        assertEquals("orders", run.getContext().getVariable("topic"));
        assertFalse(callerInputs.containsKey("topic"));

        WorkflowStartedEvent started = (WorkflowStartedEvent) run.getUncommittedEvents().stream()
                .filter(WorkflowStartedEvent.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertEquals("orders", started.inputs().get("topic"));
        assertEquals("1.0.0", started.workflowVersion());
    }

    @Test
    void create_rejectsInvalidDefinitionBeforeCreatingRun() {
        WorkflowDefinition definition = workflow(
                "invalid-create",
                List.of(),
                Map.of(),
                CompensationPolicy.disabled());

        GamelanException error = assertThrows(GamelanException.class,
                () -> WorkflowRun.create(TENANT, definition, Map.of()));

        assertEquals(ErrorCode.WORKFLOW_INVALID_DEFINITION, error.getErrorCode());
        assertTrue(error.getSafeMessage().contains("Workflow must have at least one node"));
    }

    @Test
    void failNode_retriesUntilConfiguredMaxAttempts() {
        NodeDefinition node = node("critical", new RetryPolicy(
                3,
                Duration.ZERO,
                Duration.ZERO,
                1.0,
                List.of()),
                true);
        WorkflowRun run = WorkflowRun.create(TENANT, workflow("retry", List.of(node), Map.of(), CompensationPolicy.disabled()), Map.of());
        NodeId nodeId = node.id();

        run.start();
        run.startNode(nodeId, 1);
        run.failNode(nodeId, 1, error());

        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(2, run.getNodeExecution(nodeId).getAttempt());
        assertEquals(List.of(nodeId), run.getPendingNodes());

        run.startNode(nodeId, 2);
        run.failNode(nodeId, 2, error());

        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(3, run.getNodeExecution(nodeId).getAttempt());
        assertEquals(List.of(nodeId), run.getPendingNodes());

        run.startNode(nodeId, 3);
        run.failNode(nodeId, 3, error());

        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(nodeId).getStatus());
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertFalse(run.isCompensating());
    }

    @Test
    void failNode_recordsRetryDueTimeAndReserveWaitsUntilDue() {
        RetryPolicy retryPolicy = new RetryPolicy(
                2,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                1.0,
                List.of());
        NodeDefinition node = node("retrying", retryPolicy, true);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("retry-delay", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.failNode(node.id(), 1, error());

        NodeExecution execution = run.getNodeExecution(node.id());
        assertEquals(NodeExecutionStatus.RETRYING, execution.getStatus());
        assertEquals(2, execution.getAttempt());
        assertTrue(execution.getRetryAt().isAfter(Instant.now()));
        assertTrue(run.reserveNodeForDispatch(node.id(), execution.getRetryAt().minusMillis(1)).isEmpty());
        assertEquals(NodeExecutionStatus.RETRYING, execution.getStatus());

        NodeFailedEvent failed = (NodeFailedEvent) run.getUncommittedEvents().stream()
                .filter(NodeFailedEvent.class::isInstance)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(execution.getRetryAt(), failed.retryAt());

        assertTrue(run.reserveNodeForDispatch(node.id(), execution.getRetryAt()).isPresent());
        assertEquals(NodeExecutionStatus.RUNNING, execution.getStatus());
    }

    @Test
    void fail_doesNotCompensateWhenPolicyIsDisabled() {
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("disabled-compensation", List.of(node("start")), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.fail(error());

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertFalse(run.isCompensating());
    }

    @Test
    void failNode_completesWhenOnlyNonCriticalWorkFails() {
        NodeDefinition optional = node("optional", RetryPolicy.none(), false);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("optional-failure", List.of(optional), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(optional.id(), 1);
        run.failNode(optional.id(), 1, error());

        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(optional.id()).getStatus());
        assertEquals(RunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void failNode_failsWhenNonCriticalFailureBlocksUnresolvedCriticalWork() {
        NodeDefinition optional = node("optional", RetryPolicy.none(), false);
        NodeDefinition criticalChild = node(
                "critical-child",
                List.of(optional.id()),
                RetryPolicy.none(),
                true);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("blocked-critical", List.of(optional, criticalChild), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(optional.id(), 1);
        run.failNode(optional.id(), 1, error());

        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(optional.id()).getStatus());
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertThrows(GamelanException.class, () -> run.getNodeExecution(criticalChild.id()));
    }

    @Test
    void completeNode_acceptsNullOutputAsEmptyOutput() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("null-output", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);

        assertDoesNotThrow(() -> run.completeNode(node.id(), 1, null));
        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(node.id()).getStatus());
        assertTrue(run.getNodeExecution(node.id()).getOutput().isEmpty());
        assertEquals(RunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void startNode_rejectsStaleAttempt() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("stale-attempt", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();

        assertThrows(GamelanException.class, () -> run.startNode(node.id(), 2));
        assertEquals(NodeExecutionStatus.PENDING, run.getNodeExecution(node.id()).getStatus());
        assertEquals(List.of(node.id()), run.getPendingNodes());
    }

    @Test
    void start_isIdempotentWhenAlreadyRunning() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("idempotent-start", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        long scheduledEvents = countEvents(run, NodeScheduledEvent.class);

        run.start();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(List.of(node.id()), run.getPendingNodes());
        assertEquals(scheduledEvents, countEvents(run, NodeScheduledEvent.class));
    }

    @Test
    void start_resumesRestoredPendingRunAndSchedulesStartNodes() {
        NodeDefinition node = node("start");
        WorkflowDefinition definition = workflow(
                "pending-resume",
                List.of(node),
                Map.of(),
                CompensationPolicy.disabled());
        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot(
                WorkflowRunId.generate(),
                TENANT,
                definition.id(),
                definition.version(),
                RunStatus.PENDING,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                Map.of(),
                null,
                Instant.now(),
                Instant.now(),
                null,
                1);
        WorkflowRun run = WorkflowRun.restore(snapshot, definition);

        run.start();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(List.of(node.id()), run.getPendingNodes());
        assertEquals(1, countEvents(run, NodeScheduledEvent.class));
    }

    @Test
    void start_rejectsInvalidDefinitionBeforeMutation() {
        WorkflowDefinition definition = workflow(
                "invalid-start",
                List.of(),
                Map.of(),
                CompensationPolicy.disabled());
        WorkflowRun run = WorkflowRun.restore(new WorkflowRunSnapshot(
                WorkflowRunId.generate(),
                TENANT,
                definition.id(),
                definition.version(),
                RunStatus.CREATED,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                Map.of(),
                null,
                Instant.now(),
                null,
                null,
                1), definition);

        GamelanException error = assertThrows(GamelanException.class, run::start);

        assertEquals(ErrorCode.WORKFLOW_INVALID_DEFINITION, error.getErrorCode());
        assertTrue(error.getSafeMessage().contains("Workflow must have at least one node"));
        assertEquals(RunStatus.CREATED, run.getStatus());
        assertTrue(run.getPendingNodes().isEmpty());
        assertEquals(0, countEvents(run, NodeScheduledEvent.class));
        assertTrue(run.getUncommittedEvents().isEmpty());
    }

    @Test
    void scheduleNode_whenAlreadyPending_doesNotDuplicateQueueOrEvent() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("idempotent-schedule", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        NodeExecution firstExecution = run.getNodeExecution(node.id());
        long scheduledEvents = countEvents(run, NodeScheduledEvent.class);

        NodeExecution repeatedExecution = run.scheduleNode(node.id());

        assertSame(firstExecution, repeatedExecution);
        assertEquals(List.of(node.id()), run.getPendingNodes());
        assertEquals(scheduledEvents, countEvents(run, NodeScheduledEvent.class));
    }

    @Test
    void startNode_whenAlreadyRunning_doesNotDuplicateEvent() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("idempotent-node-start", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        long startedEvents = countEvents(run, NodeStartedEvent.class);

        run.startNode(node.id(), 1);

        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(node.id()).getStatus());
        assertTrue(run.getPendingNodes().isEmpty());
        assertEquals(startedEvents, countEvents(run, NodeStartedEvent.class));
    }

    @Test
    void reserveNodeForDispatch_claimsPendingNodeOnlyOnce() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("reserve-dispatch", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();

        var firstReservation = run.reserveNodeForDispatch(node.id());
        long startedEvents = countEvents(run, NodeStartedEvent.class);
        var secondReservation = run.reserveNodeForDispatch(node.id());

        assertTrue(firstReservation.isPresent());
        assertTrue(secondReservation.isEmpty());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(node.id()).getStatus());
        assertTrue(run.getPendingNodes().isEmpty());
        assertEquals(1, startedEvents);
        assertEquals(startedEvents, countEvents(run, NodeStartedEvent.class));
    }

    @Test
    void completeNode_whenAlreadyCompleted_doesNotOverwriteResultOrDuplicateEvent() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("idempotent-complete", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.completeNode(node.id(), 1, Map.of("result", "ok"));
        long completedEvents = countEvents(run, NodeCompletedEvent.class);

        run.completeNode(node.id(), 1, Map.of("result", "late-duplicate"));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals("ok", run.getNodeExecution(node.id()).getOutput().get("result"));
        assertEquals(List.of(node.id().value()), run.createSnapshot().executionPath());
        assertEquals(completedEvents, countEvents(run, NodeCompletedEvent.class));
    }

    @Test
    void failNode_whenAlreadyFailed_doesNotDuplicateFailurePathOrEvent() {
        NodeDefinition node = node("optional", RetryPolicy.none(), false);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("idempotent-failure", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.failNode(node.id(), 1, error());
        long failedEvents = countEvents(run, NodeFailedEvent.class);

        run.failNode(node.id(), 1, error());

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(node.id()).getStatus());
        assertEquals(List.of(node.id().value() + ":FAILED"), run.createSnapshot().executionPath());
        assertEquals(failedEvents, countEvents(run, NodeFailedEvent.class));
    }

    @Test
    void signal_bufferedBeforeSuspend_resumesWhenSuspendingOnTargetNode() {
        NodeDefinition node = node("approval");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("early-signal", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.signal(new Signal("approved", node.id(), Map.of("approval.result", "yes"), Instant.now()));
        run.suspend("waiting for approval", node.id());

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
    }

    @Test
    void signal_withoutTargetResumesCurrentSuspension() {
        NodeDefinition node = node("approval");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("untargeted-signal", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.suspend("waiting for approval", node.id());
        run.signal(new Signal("approved", null, Map.of("approval.result", "yes"), Instant.now()));

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
    }

    @Test
    void signal_withoutTargetBufferedBeforeSuspendResumesLater() {
        NodeDefinition node = node("approval");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("early-untargeted-signal", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.signal(new Signal("approved", null, Map.of("approval.result", "yes"), Instant.now()));
        run.suspend("waiting for approval", node.id());

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
    }

    @Test
    void signal_acceptsNullPayloadAsEmptyResumeData() {
        NodeDefinition node = node("approval");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("null-signal-payload", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.suspend("waiting for approval", node.id());

        assertDoesNotThrow(() -> run.signal(new Signal("approved", node.id(), null, Instant.now())));
        assertEquals(RunStatus.RUNNING, run.getStatus());
    }

    @Test
    void restore_preservesSuspensionSoSignalCanResumeAfterRestart() {
        NodeDefinition node = node("approval");
        WorkflowDefinition definition = workflow(
                "restore-suspension",
                List.of(node),
                Map.of(),
                CompensationPolicy.disabled());
        WorkflowRun original = WorkflowRun.create(TENANT, definition, Map.of());

        original.start();
        original.startNode(node.id(), 1);
        original.suspend("waiting for approval", node.id());

        WorkflowRun restored = WorkflowRun.restore(original.createSnapshot(), definition);
        restored.signal(new Signal("approved", node.id(), Map.of("approval.result", "yes"), Instant.now()));

        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals("yes", restored.getContext().getVariable("approval.result"));
    }

    @Test
    void restore_preservesPendingSignalsSoLaterSuspendCanResumeAfterRestart() {
        NodeDefinition node = node("approval");
        WorkflowDefinition definition = workflow(
                "restore-pending-signal",
                List.of(node),
                Map.of(),
                CompensationPolicy.disabled());
        WorkflowRun original = WorkflowRun.create(TENANT, definition, Map.of());

        original.start();
        original.startNode(node.id(), 1);
        original.signal(new Signal("approved", node.id(), Map.of("approval.result", "yes"), Instant.now()));

        WorkflowRun restored = WorkflowRun.restore(original.createSnapshot(), definition);
        restored.suspend("waiting for approval", node.id());

        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals("yes", restored.getContext().getVariable("approval.result"));
    }

    @Test
    void restore_preservesCompensationProgress() {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second", List.of(first.id()), RetryPolicy.none(), true);
        WorkflowDefinition definition = workflow(
                "restore-compensation",
                List.of(first, second),
                Map.of(),
                CompensationPolicy.enabledDefault());
        WorkflowRun original = WorkflowRun.create(TENANT, definition, Map.of());

        original.start();
        original.startNode(first.id(), 1);
        original.completeNode(first.id(), 1, Map.of());
        original.fail(error());

        WorkflowRun restored = WorkflowRun.restore(original.createSnapshot(), definition);

        assertNull(original.getCompletedAt());
        assertNull(original.createSnapshot().completedAt());
        assertEquals(RunStatus.COMPENSATING, restored.getStatus());
        assertNull(restored.getCompletedAt());
        assertEquals(first.id(), restored.getNextNodeToCompensate());
        restored.compensateNode(first.id());
        assertEquals(RunStatus.COMPENSATING, restored.getStatus());
        assertTrue(restored.getCompensationState().isComplete());
        restored.completeCompensation();
        assertEquals(RunStatus.COMPENSATED, restored.getStatus());
    }

    @Test
    void fail_withCompensationStartsActiveCompensationWithoutCompletedAt() {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second", List.of(first.id()), RetryPolicy.none(), true);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("fail-compensation-active", List.of(first, second), Map.of(), CompensationPolicy.enabledDefault()),
                Map.of());

        run.start();
        run.startNode(first.id(), 1);
        run.completeNode(first.id(), 1, Map.of("result", "ok"));
        run.fail(error());

        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertNull(run.getCompletedAt());
        assertNull(run.createSnapshot().completedAt());
        assertEquals(first.id(), run.getNextNodeToCompensate());
    }

    @Test
    void cancel_withCompensationStartsActiveCompensationWithoutCompletedAt() {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second", List.of(first.id()), RetryPolicy.none(), true);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("cancel-compensation-active", List.of(first, second), Map.of(), CompensationPolicy.enabledDefault()),
                Map.of());

        run.start();
        run.startNode(first.id(), 1);
        run.completeNode(first.id(), 1, Map.of("result", "ok"));
        run.cancel("operator stop");

        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertNull(run.getCompletedAt());
        assertNull(run.createSnapshot().completedAt());
        assertEquals(first.id(), run.getNextNodeToCompensate());
    }

    @Test
    void completeCompensation_marksRemainingNodesCompensatedAndSetsCompletedAt() {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second", List.of(first.id()), RetryPolicy.none(), true);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("complete-compensation-finalizes-state", List.of(first, second), Map.of(),
                        CompensationPolicy.enabledDefault()),
                Map.of());

        run.start();
        run.startNode(first.id(), 1);
        run.completeNode(first.id(), 1, Map.of("result", "ok"));
        run.fail(error());
        run.completeCompensation();

        assertEquals(RunStatus.COMPENSATED, run.getStatus());
        assertNotNull(run.getCompletedAt());
        assertTrue(run.getCompensationState().nodesToCompensate().isEmpty());
        assertEquals(List.of(first.id()), run.getCompensationState().compensatedNodes());
        assertTrue(run.getCompensationState().isComplete());
    }

    @Test
    void fail_withCompensationOrdersNodesByReverseExecutionPath() {
        NodeDefinition first = node("first");
        NodeDefinition second = node("second");
        NodeDefinition third = node("third", List.of(first.id(), second.id()), RetryPolicy.none(), true);
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("compensation-reverse-order", List.of(first, second, third), Map.of(),
                        CompensationPolicy.enabledDefault()),
                Map.of());

        run.start();
        run.startNode(first.id(), 1);
        run.completeNode(first.id(), 1, Map.of("result", "first"));
        run.startNode(second.id(), 1);
        run.completeNode(second.id(), 1, Map.of("result", "second"));
        run.startNode(third.id(), 1);
        run.failNode(third.id(), 1, error());

        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertEquals(List.of(second.id(), first.id()), run.getCompensationState().nodesToCompensate());
    }

    @Test
    void cancel_rejectsCompensatingRunWithoutChangingState() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("cancel-compensating", List.of(node), Map.of(), CompensationPolicy.enabledDefault()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.cancel("operator stop");

        GamelanException error = assertThrows(GamelanException.class, () -> run.cancel("second stop"));

        assertEquals(ErrorCode.RUN_INVALID_STATE, error.getErrorCode());
        assertEquals("Invalid state transition from COMPENSATING to CANCELLED", error.getSafeMessage());
        assertEquals(RunStatus.COMPENSATING, run.getStatus());
    }

    @Test
    void completeCompensation_rejectsNonCompensatingRunWithoutChangingState() {
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("complete-compensation-invalid", List.of(node("start")), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();

        GamelanException error = assertThrows(GamelanException.class, run::completeCompensation);

        assertEquals(ErrorCode.RUN_INVALID_STATE, error.getErrorCode());
        assertEquals("Invalid state transition from RUNNING to COMPENSATED", error.getSafeMessage());
        assertEquals(RunStatus.RUNNING, run.getStatus());
    }

    @Test
    void fromEvents_rehydratesCompletedRunStateWithoutUncommittedEvents() {
        NodeDefinition node = node("start");
        WorkflowDefinition definition = workflow("rehydrate", List.of(node), Map.of(), CompensationPolicy.disabled());
        WorkflowRun original = WorkflowRun.create(TENANT, definition, Map.of());

        original.start();
        original.startNode(node.id(), 1);
        original.completeNode(node.id(), 1, Map.of("result", "ok"));

        List<ExecutionEvent> events = List.copyOf(original.getUncommittedEvents());
        WorkflowRun restored = WorkflowRun.fromEvents(original.id(), TENANT, definition, events);

        assertEquals(RunStatus.COMPLETED, restored.getStatus());
        assertTrue(restored.getPendingNodes().isEmpty());
        assertEquals(NodeExecutionStatus.COMPLETED, restored.getNodeExecution(node.id()).getStatus());
        assertEquals("ok", restored.getNodeExecution(node.id()).getOutput().get("result"));
        assertEquals("ok", restored.getContext().getVariable("start.result"));
        assertEquals(events.size(), restored.getContext().getEvents().size());
        assertEquals(events.size(), restored.getVersion());
        assertTrue(restored.getUncommittedEvents().isEmpty());
    }

    @Test
    void fromEvents_rejectsEventForDifferentRun() {
        NodeDefinition node = node("start");
        WorkflowDefinition definition = workflow(
                "mixed-run-stream",
                List.of(node),
                Map.of(),
                CompensationPolicy.disabled());
        WorkflowRun original = WorkflowRun.create(TENANT, definition, Map.of());
        original.start();
        List<ExecutionEvent> events = new ArrayList<>(original.getUncommittedEvents());
        events.add(new NodeScheduledEvent(
                "mixed-run-event",
                WorkflowRunId.generate(),
                node.id(),
                1,
                Instant.now()));

        GamelanException error = assertThrows(GamelanException.class,
                () -> WorkflowRun.fromEvents(original.id(), TENANT, definition, events));

        assertEquals(ErrorCode.WORKFLOW_INVALID_DEFINITION, error.getErrorCode());
        assertTrue(error.getSafeMessage().contains("event for another run"));
    }

    @Test
    void fromEvents_rejectsStartedEventWithDifferentTenant() {
        NodeDefinition node = node("start");
        WorkflowDefinition definition = workflow(
                "wrong-tenant-stream",
                List.of(node),
                Map.of(),
                CompensationPolicy.disabled());
        WorkflowRun original = WorkflowRun.create(TENANT, definition, Map.of());
        List<ExecutionEvent> events = new ArrayList<>(original.getUncommittedEvents());
        WorkflowStartedEvent started = (WorkflowStartedEvent) events.getFirst();
        events.set(0, new WorkflowStartedEvent(
                started.eventId(),
                started.runId(),
                started.definitionId(),
                TenantId.of("other-tenant"),
                started.workflowVersion(),
                started.inputs(),
                started.occurredAt()));

        GamelanException error = assertThrows(GamelanException.class,
                () -> WorkflowRun.fromEvents(original.id(), TENANT, definition, events));

        assertEquals(ErrorCode.WORKFLOW_INVALID_DEFINITION, error.getErrorCode());
        assertTrue(error.getSafeMessage().contains("tenant mismatch"));
    }

    @Test
    void restore_rehydratesSnapshotWithoutSharingMutableNodeState() {
        NodeDefinition node = node("start");
        WorkflowDefinition definition = workflow("snapshot-restore", List.of(node), Map.of(), CompensationPolicy.disabled());
        WorkflowRun original = WorkflowRun.create(TENANT, definition, Map.of("topic", "orders"));

        original.start();
        original.startNode(node.id(), 1);
        original.markEventsAsCommitted();

        WorkflowRun restored = WorkflowRun.restore(original.createSnapshot(), definition);
        original.getNodeExecution(node.id()).setStatus(NodeExecutionStatus.FAILED);

        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals(NodeExecutionStatus.RUNNING, restored.getNodeExecution(node.id()).getStatus());
        assertEquals("orders", restored.getContext().getVariable("topic"));
        assertEquals(original.getVersion(), restored.getVersion());
        assertTrue(restored.getUncommittedEvents().isEmpty());
    }

    @Test
    void restore_rejectsSnapshotNodeMissingFromSuppliedDefinition() {
        NodeDefinition node = node("start");
        WorkflowDefinition originalDefinition = workflow(
                "snapshot-node-index",
                List.of(node),
                Map.of(),
                CompensationPolicy.disabled());
        WorkflowRun original = WorkflowRun.create(TENANT, originalDefinition, Map.of());
        original.start();

        WorkflowDefinition incompatibleDefinition = workflow(
                "snapshot-node-index",
                List.of(node("other")),
                Map.of(),
                CompensationPolicy.disabled());

        assertThrows(GamelanException.class,
                () -> WorkflowRun.restore(original.createSnapshot(), incompatibleDefinition));
    }

    @Test
    void createSnapshot_doesNotShareMutableNodeExecutionsWithLiveRun() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("snapshot-isolation", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);
        run.completeNode(node.id(), 1, Map.of("result", "ok"));

        WorkflowRunSnapshot snapshot = run.createSnapshot();
        assertEquals("1.0.0", snapshot.definitionVersion());
        NodeExecution snapshotExecution = snapshot.nodeExecutions().get(node.id());
        snapshotExecution.setStatus(NodeExecutionStatus.FAILED);
        snapshotExecution.setOutput(Map.of("result", "mutated"));

        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(node.id()).getStatus());
        assertEquals("ok", run.getNodeExecution(node.id()).getOutput().get("result"));
    }

    @Test
    void markEventsAsCommitted_advancesVersionByCommittedEventCount() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("version", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        int initialEventCount = run.getUncommittedEvents().size();

        run.markEventsAsCommitted();

        assertEquals(initialEventCount, run.getVersion());
        assertTrue(run.getUncommittedEvents().isEmpty());

        run.startNode(node.id(), 1);
        run.markEventsAsCommitted();

        assertEquals(initialEventCount + 1, run.getVersion());
        assertTrue(run.getUncommittedEvents().isEmpty());
    }

    @Test
    void markEventsAsCommitted_withEventSubsetCommitsOnlyPersistedEvents() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("partial-version", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        List<ExecutionEvent> events = List.copyOf(run.getUncommittedEvents());
        ExecutionEvent creationEvent = events.get(0);
        ExecutionEvent scheduledEvent = events.get(1);

        run.markEventsAsCommitted(List.of(scheduledEvent));

        assertEquals(1, run.getVersion());
        assertEquals(List.of(creationEvent), run.getUncommittedEvents());

        run.markEventsAsCommitted(List.of(creationEvent));

        assertEquals(2, run.getVersion());
        assertTrue(run.getUncommittedEvents().isEmpty());
    }

    private static NodeDefinition node(String id) {
        return node(id, RetryPolicy.none(), false);
    }

    private static NodeDefinition node(String id, RetryPolicy retryPolicy, boolean critical) {
        return node(id, List.of(), retryPolicy, critical);
    }

    private static NodeDefinition node(
            String id,
            List<NodeId> dependsOn,
            RetryPolicy retryPolicy,
            boolean critical) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                dependsOn,
                List.of(),
                retryPolicy,
                Duration.ZERO,
                critical);
    }

    private static WorkflowDefinition workflow(
            String id,
            List<NodeDefinition> nodes,
            Map<String, InputDefinition> inputs,
            CompensationPolicy compensationPolicy) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-" + id),
                TENANT,
                id,
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                nodes,
                inputs,
                Map.of(),
                null,
                RetryPolicy.none(),
                compensationPolicy);
    }

    private static ErrorInfo error() {
        return new ErrorInfo("TEST_ERROR", "boom", "", Map.of());
    }

    private static long countEvents(WorkflowRun run, Class<? extends ExecutionEvent> eventType) {
        return run.getUncommittedEvents().stream()
                .filter(eventType::isInstance)
                .count();
    }
}
