package tech.kayys.gamelan.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.engine.workflow.WorkflowRunState;

class ExecutionHistoryTest {

    @Test
    void fromEventsMapsDomainEventNames() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        ExecutionHistory history = ExecutionHistory.fromEvents(runId, List.of(
                new GenericExecutionEvent(runId, "NodeStarted", "started", Instant.EPOCH, Map.of()),
                new GenericExecutionEvent(runId, "NodeCompleted", "completed", Instant.EPOCH, Map.of()),
                new GenericExecutionEvent(runId, "NodeFailed", "failed", Instant.EPOCH, Map.of())));

        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_STARTED,
                history.getEvents().get(0).getEventType());
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED,
                history.getEvents().get(1).getEventType());
        assertEquals(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_FAILED,
                history.getEvents().get(2).getEventType());
    }

    @Test
    void fromEventsPreservesDomainPayloadAndMetadata() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeId completedNode = NodeId.of("agent-node");
        NodeId failedNode = NodeId.of("tool-node");
        Instant firstTimestamp = Instant.EPOCH;
        Instant secondTimestamp = Instant.EPOCH.plusSeconds(1);

        ExecutionHistory history = ExecutionHistory.fromEvents(runId, List.of(
                new NodeCompletedEvent(
                        "event-1",
                        runId,
                        completedNode,
                        2,
                        Map.of("answer", "ok"),
                        firstTimestamp),
                new NodeFailedEvent(
                        "event-2",
                        runId,
                        failedNode,
                        3,
                        new ErrorInfo("TOOL_TIMEOUT", "tool timed out", null, Map.of("tool", "browser")),
                        true,
                        secondTimestamp)));

        ExecutionHistory.ExecutionEventHistory completed = history.getEvents().get(0);
        ExecutionHistory.ExecutionEventHistory failed = history.getEvents().get(1);

        assertEquals("ok", completed.getPayload().get("answer"));
        assertEquals("agent-node", completed.getMetadata().get("nodeId"));
        assertEquals(2, completed.getMetadata().get("attempt"));
        assertEquals("TOOL_TIMEOUT", failed.getPayload().get("code"));
        assertEquals("tool timed out", failed.getPayload().get("message"));
        assertEquals("tool-node", failed.getMetadata().get("nodeId"));
        assertEquals(true, failed.getMetadata().get("willRetry"));
        assertEquals(2, history.getStatistics().getTotalEvents());
        assertEquals(firstTimestamp, history.getCreated());
        assertEquals(secondTimestamp, history.getLastUpdated());
    }

    @Test
    void fromEventsRejectsEventForDifferentRun() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        WorkflowRunId otherRunId = WorkflowRunId.of("run-2");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionHistory.fromEvents(runId, List.of(new GenericExecutionEvent(
                        "event-1",
                        otherRunId,
                        "NodeCompleted",
                        "completed",
                        Instant.EPOCH,
                        Map.of()))));

        assertEquals("Execution history event run id mismatch: expected run-1 but found run-2",
                error.getMessage());
    }

    @Test
    void fromEventsRejectsEventWithoutRunId() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionHistory.fromEvents(runId, List.of(new GenericExecutionEvent(
                        "NodeCompleted",
                        "completed",
                        Instant.EPOCH,
                        Map.of()))));

        assertEquals("Execution history event has no run id: NodeCompleted", error.getMessage());
    }

    @Test
    void fromEventsRejectsConflictingTenantMetadata() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionHistory.fromEvents(runId, List.of(
                        new WorkflowStartedEvent(
                                "event-1",
                                runId,
                                WorkflowDefinitionId.of("wf-1"),
                                TenantId.of("tenant-a"),
                                Map.of(),
                                Instant.EPOCH),
                        new GenericExecutionEvent(
                                "event-2",
                                runId,
                                "NodeCompleted",
                                "completed",
                                Instant.EPOCH.plusSeconds(1),
                                Map.of("tenantId", "tenant-b")))));

        assertEquals("Execution history event tenant id mismatch: expected tenant-a but found tenant-b",
                error.getMessage());
    }

    @Test
    void fromEventsRejectsConflictingWorkflowIdentityMetadata() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExecutionHistory.fromEvents(runId, List.of(
                        new WorkflowStartedEvent(
                                "event-1",
                                runId,
                                WorkflowDefinitionId.of("wf-1"),
                                TenantId.of("tenant-a"),
                                "1.0.0",
                                Map.of(),
                                Instant.EPOCH),
                        new GenericExecutionEvent(
                                "event-2",
                                runId,
                                "NodeCompleted",
                                "completed",
                                Instant.EPOCH.plusSeconds(1),
                                Map.of(
                                        "definitionId", "wf-2",
                                        "workflowVersion", "2.0.0")))));

        assertEquals("Execution history event workflow id mismatch: expected wf-1 but found wf-2",
                error.getMessage());
    }

    @Test
    void fromEventsInfersTenantFromWorkflowStartedEvent() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        ExecutionHistory history = ExecutionHistory.fromEvents(runId, List.of(new WorkflowStartedEvent(
                "event-1",
                runId,
                WorkflowDefinitionId.of("wf-1"),
                TenantId.of("tenant-a"),
                Map.of(),
                Instant.EPOCH)));

        assertEquals("tenant-a", history.getTenantId());
    }

    @Test
    void fromEventsInfersWorkflowIdentityFromWorkflowStartedEvent() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        ExecutionHistory history = ExecutionHistory.fromEvents(runId, List.of(new WorkflowStartedEvent(
                "event-1",
                runId,
                WorkflowDefinitionId.of("wf-1"),
                TenantId.of("tenant-a"),
                "1.2.3",
                Map.of(),
                Instant.EPOCH)));

        assertEquals(WorkflowId.of("wf-1"), history.getWorkflowId());
        assertEquals("1.2.3", history.getWorkflowVersion());
        assertEquals("wf-1", history.getEvents().getFirst().getMetadata().get("definitionId"));
        assertEquals("1.2.3", history.getEvents().getFirst().getMetadata().get("workflowVersion"));
    }

    @Test
    void fromEventsInfersTenantFromGenericEventMetadata() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        ExecutionHistory history = ExecutionHistory.fromEvents(runId, List.of(new GenericExecutionEvent(
                "event-1",
                runId,
                "NodeCompleted",
                "completed",
                Instant.EPOCH,
                Map.of("tenantId", "tenant-b"))));

        assertEquals("tenant-b", history.getTenantId());
    }

    @Test
    void fromEventsInfersWorkflowIdentityFromGenericEventMetadata() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        ExecutionHistory history = ExecutionHistory.fromEvents(runId, List.of(new GenericExecutionEvent(
                "event-1",
                runId,
                "WorkflowStarted",
                "started",
                Instant.EPOCH,
                Map.of(
                        "definitionId", "wf-2",
                        "workflowVersion", "2.1.0"))));

        assertEquals(WorkflowId.of("wf-2"), history.getWorkflowId());
        assertEquals("2.1.0", history.getWorkflowVersion());
    }

    @Test
    void fromSnapshotUsesSnapshotWorkflowVersion() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot(
                runId,
                TenantId.of("tenant-a"),
                WorkflowDefinitionId.of("wf-3"),
                "3.0.0",
                RunStatus.CREATED,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                Map.of(),
                null,
                Instant.EPOCH,
                null,
                null,
                0L);

        ExecutionHistory history = ExecutionHistory.fromSnapshot(snapshot, List.of(), List.of());

        assertEquals(WorkflowId.of("wf-3"), history.getWorkflowId());
        assertEquals("3.0.0", history.getWorkflowVersion());
    }

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesSnapshotsAndMetadata() {
        Map<String, Object> inputPayload = nestedPayload("prompt", "plan");
        Map<Instant, Map<String, Object>> inputSnapshots = new LinkedHashMap<>();
        inputSnapshots.put(Instant.EPOCH, inputPayload);
        Map<String, Object> metadata = nestedPayload("source", "history");

        ExecutionHistory history = ExecutionHistory.builder()
                .runId(WorkflowRunId.of("run-1"))
                .workflowId(WorkflowId.of("workflow-1"))
                .tenantId("tenant-1")
                .inputSnapshots(inputSnapshots)
                .outputSnapshots(Map.of(Instant.EPOCH.plusSeconds(1), nestedPayload("result", "ok")))
                .metadata(metadata)
                .build();

        ((Map<String, Object>) inputPayload.get("nested")).put("prompt", "mutated");
        inputSnapshots.put(Instant.EPOCH.plusSeconds(2), Map.of("late", "ignored"));
        ((Map<String, Object>) metadata.get("nested")).put("source", "mutated");

        assertEquals(
                "plan",
                ((Map<String, Object>) history.getInputSnapshots().get(Instant.EPOCH).get("nested")).get("prompt"));
        assertEquals(
                "history",
                ((Map<String, Object>) history.getMetadata().get("nested")).get("source"));
        assertFalse(history.getInputSnapshots().containsKey(Instant.EPOCH.plusSeconds(2)));
        assertThrows(UnsupportedOperationException.class,
                () -> history.getInputSnapshots().put(Instant.EPOCH.plusSeconds(3), Map.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> history.getInputSnapshots().get(Instant.EPOCH).put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> history.getMetadata().put("x", "y"));
    }

    @Test
    void executionEventHistoryDefensivelyCopiesAndFreezesPayloadAndMetadata() {
        Map<String, Object> payload = nestedPayload("message", "hello");
        Map<String, Object> metadata = nestedPayload("executor", "local-agent");

        ExecutionHistory.ExecutionEventHistory event = ExecutionHistory.ExecutionEventHistory.builder()
                .eventId("event-1")
                .eventType(ExecutionHistory.ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED)
                .timestamp(Instant.EPOCH)
                .payload(payload)
                .metadata(metadata)
                .build();

        assertFrozenNestedPayload(event.getPayload(), payload, "message", "hello");
        assertFrozenNestedPayload(event.getMetadata(), metadata, "executor", "local-agent");
    }

    @Test
    void stateTransitionDefensivelyCopiesAndFreezesMetadata() {
        Map<String, Object> metadata = nestedPayload("reason", "signal");

        ExecutionHistory.StateTransition transition = ExecutionHistory.StateTransition.builder()
                .fromState(WorkflowRunState.WAITING)
                .toState(WorkflowRunState.RUNNING)
                .timestamp(Instant.EPOCH)
                .metadata(metadata)
                .build();

        assertFrozenNestedPayload(transition.getMetadata(), metadata, "reason", "signal");
    }

    @Test
    void executionStatisticsDefensivelyCopiesAndFreezesMetricsAndCounts() {
        Map<String, Integer> nodeTypeCounts = new HashMap<>();
        nodeTypeCounts.put("AGENT_LOOP", 1);
        Map<String, Duration> nodeTypeDurations = new HashMap<>();
        nodeTypeDurations.put("AGENT_LOOP", Duration.ofMillis(10));
        Map<String, Object> metrics = nestedPayload("tokens", 128);

        ExecutionHistory.ExecutionStatistics statistics = ExecutionHistory.ExecutionStatistics.builder()
                .nodeTypeCounts(nodeTypeCounts)
                .nodeTypeDurations(nodeTypeDurations)
                .metrics(metrics)
                .build();

        nodeTypeCounts.put("AGENT_LOOP", 2);
        nodeTypeDurations.put("AGENT_LOOP", Duration.ofSeconds(1));

        assertEquals(1, statistics.getNodeTypeCounts().get("AGENT_LOOP"));
        assertEquals(Duration.ofMillis(10), statistics.getNodeTypeDurations().get("AGENT_LOOP"));
        assertFrozenNestedPayload(statistics.getMetrics(), metrics, "tokens", 128);
        assertThrows(UnsupportedOperationException.class, () -> statistics.getNodeTypeCounts().put("x", 1));
        assertThrows(UnsupportedOperationException.class,
                () -> statistics.getNodeTypeDurations().put("x", Duration.ZERO));
    }

    private static Map<String, Object> nestedPayload(String key, Object value) {
        Map<String, Object> nested = new HashMap<>();
        nested.put(key, value);
        Map<String, Object> payload = new HashMap<>();
        payload.put("nested", nested);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static void assertFrozenNestedPayload(
            Map<String, Object> eventPayload,
            Map<String, Object> callerPayload,
            String key,
            Object expectedValue) {

        Map<String, Object> callerNested = (Map<String, Object>) callerPayload.get("nested");
        callerPayload.put("late", "ignored");
        callerNested.put(key, "mutated");

        Map<String, Object> eventNested = (Map<String, Object>) eventPayload.get("nested");
        assertEquals(expectedValue, eventNested.get(key));
        assertFalse(eventPayload.containsKey("late"));
        assertThrows(UnsupportedOperationException.class, () -> eventPayload.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> eventNested.put("x", "y"));
    }
}
