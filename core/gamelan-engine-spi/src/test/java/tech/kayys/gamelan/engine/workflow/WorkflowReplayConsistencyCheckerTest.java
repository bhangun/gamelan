package tech.kayys.gamelan.engine.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;

class WorkflowReplayConsistencyCheckerTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("start");

    @Test
    void compareReturnsConsistentWhenSnapshotMatchesReplay() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = completedRun(definition);
        List<ExecutionEvent> events = List.copyOf(run.getUncommittedEvents());
        run.markEventsAsCommitted(events);

        WorkflowReplayConsistencyChecker.Report report = WorkflowReplayConsistencyChecker.compare(
                run.createSnapshot(),
                definition,
                events);

        assertTrue(report.consistent());
        assertEquals(WorkflowReplayConsistencyChecker.Status.CONSISTENT, report.status());
        assertFalse(report.drift());
        assertFalse(report.unavailable());
        assertTrue(report.mismatches().isEmpty());
    }

    @Test
    void compareReportsSnapshotDrift() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = completedRun(definition);
        List<ExecutionEvent> events = List.copyOf(run.getUncommittedEvents());
        run.markEventsAsCommitted(events);
        WorkflowRunSnapshot snapshot = run.createSnapshot();
        WorkflowRunSnapshot drifted = new WorkflowRunSnapshot(
                snapshot.id(),
                snapshot.tenantId(),
                snapshot.definitionId(),
                snapshot.definitionVersion(),
                RunStatus.RUNNING,
                Map.of("unexpected", true),
                snapshot.nodeExecutions(),
                snapshot.executionPath(),
                snapshot.suspensionInfo(),
                snapshot.pendingSignals(),
                snapshot.compensationState(),
                snapshot.createdAt(),
                snapshot.startedAt(),
                snapshot.completedAt(),
                snapshot.version());

        WorkflowReplayConsistencyChecker.Report report = WorkflowReplayConsistencyChecker.compare(
                drifted,
                definition,
                events);

        assertFalse(report.consistent());
        assertEquals(WorkflowReplayConsistencyChecker.Status.DRIFT, report.status());
        assertTrue(report.drift());
        assertFalse(report.unavailable());
        assertTrue(hasMismatch(report, "status"));
        assertTrue(hasMismatch(report, "variables"));
    }

    @Test
    void compareReportsReplayFailureForIncompleteEventStream() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = completedRun(definition);

        WorkflowReplayConsistencyChecker.Report report = WorkflowReplayConsistencyChecker.compare(
                run.createSnapshot(),
                definition,
                List.of());

        assertFalse(report.consistent());
        assertEquals(WorkflowReplayConsistencyChecker.Status.DRIFT, report.status());
        assertTrue(report.drift());
        assertFalse(report.unavailable());
        assertEquals("replay", report.mismatches().getFirst().field());
    }

    @Test
    void reportTracksUnavailableStatusSeparately() {
        WorkflowReplayConsistencyChecker.Report report = new WorkflowReplayConsistencyChecker.Report(
                WorkflowRunId.of("run-unavailable"),
                WorkflowReplayConsistencyChecker.Status.UNAVAILABLE,
                List.of(new WorkflowReplayConsistencyChecker.Mismatch(
                        "replayConsistency",
                        "available",
                        "EventStore unavailable")));

        assertFalse(report.consistent());
        assertFalse(report.drift());
        assertTrue(report.unavailable());
        assertEquals(WorkflowReplayConsistencyChecker.Status.UNAVAILABLE, report.status());
    }

    @Test
    void reportThrowsStandardExceptionWhenInconsistent() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = completedRun(definition);

        WorkflowReplayConsistencyChecker.Report report = WorkflowReplayConsistencyChecker.compare(
                run.createSnapshot(),
                definition,
                List.of());

        GamelanException error = assertThrows(GamelanException.class, report::throwIfInconsistent);
        assertEquals(ErrorCode.STORAGE_SERIALIZATION_FAILED, error.getErrorCode());
    }

    private static boolean hasMismatch(WorkflowReplayConsistencyChecker.Report report, String field) {
        return report.mismatches().stream()
                .anyMatch(mismatch -> field.equals(mismatch.field()));
    }

    private static WorkflowRun completedRun(WorkflowDefinition definition) {
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("input", "value"));
        run.start();
        run.startNode(NODE_ID, 1);
        run.completeNode(NODE_ID, 1, Map.of("result", "ok"));
        return run;
    }

    private static WorkflowDefinition workflow() {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-replay"),
                TENANT,
                "replay-check",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(node()),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node() {
        return new NodeDefinition(
                NODE_ID,
                NODE_ID.value(),
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                true);
    }
}
