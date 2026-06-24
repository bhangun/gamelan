package tech.kayys.gamelan.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class ExecutionEventPayloadTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");
    private static final Instant NOW = Instant.EPOCH;

    @Test
    void workflowStartedEventDefensivelyCopiesAndFreezesInputs() {
        Map<String, Object> inputs = nestedPayload("prompt", "plan");

        WorkflowStartedEvent event = new WorkflowStartedEvent(
                "event-1",
                RUN_ID,
                WorkflowDefinitionId.of("workflow-1"),
                TenantId.of("tenant-1"),
                inputs,
                NOW);

        assertFrozenNestedPayload(event.inputs(), inputs, "prompt", "plan");
        assertEquals("unknown", event.workflowVersion());
    }

    @Test
    void workflowStartedEventPreservesWorkflowVersion() {
        WorkflowStartedEvent event = new WorkflowStartedEvent(
                "event-1",
                RUN_ID,
                WorkflowDefinitionId.of("workflow-1"),
                TenantId.of("tenant-1"),
                "2.0.0",
                Map.of(),
                NOW);

        assertEquals("2.0.0", event.workflowVersion());
    }

    @Test
    void nodeCompletedEventDefensivelyCopiesAndFreezesOutput() {
        Map<String, Object> output = nestedPayload("answer", 42);

        NodeCompletedEvent event = new NodeCompletedEvent(
                "event-1",
                RUN_ID,
                NODE_ID,
                1,
                output,
                NOW);

        assertFrozenNestedPayload(event.output(), output, "answer", 42);
    }

    @Test
    void workflowCompletedEventDefensivelyCopiesAndFreezesOutputs() {
        Map<String, Object> outputs = nestedPayload("summary", "done");

        WorkflowCompletedEvent event = new WorkflowCompletedEvent("event-1", RUN_ID, outputs, NOW);

        assertFrozenNestedPayload(event.outputs(), outputs, "summary", "done");
    }

    @Test
    void workflowResumedEventDefensivelyCopiesAndFreezesResumeData() {
        Map<String, Object> resumeData = nestedPayload("approved", true);

        WorkflowResumedEvent event = new WorkflowResumedEvent("event-1", RUN_ID, resumeData, "task-1", NOW);

        assertFrozenNestedPayload(event.resumeData(), resumeData, "approved", true);
    }

    @Test
    void genericExecutionEventDefensivelyCopiesAndFreezesMetadata() {
        Map<String, Object> metadata = nestedPayload("queue", "agent-local");

        GenericExecutionEvent event = new GenericExecutionEvent("event-1", RUN_ID, "Custom", "message", NOW, metadata);

        assertFrozenNestedPayload(event.metadata(), metadata, "queue", "agent-local");
    }

    @Test
    void mapPayloadEventsNormalizeNullMapsToEmptyMaps() {
        assertFalse(new NodeCompletedEvent("event-1", RUN_ID, NODE_ID, 1, null, NOW).output().containsKey("x"));
        assertFalse(new WorkflowCompletedEvent("event-2", RUN_ID, null, NOW).outputs().containsKey("x"));
        assertFalse(new WorkflowResumedEvent("event-3", RUN_ID, null, null, NOW).resumeData().containsKey("x"));
        assertFalse(new GenericExecutionEvent("event-4", RUN_ID, "Custom", "message", NOW, null).metadata()
                .containsKey("x"));
    }

    @Test
    void compensationEventsDefensivelyCopyAndFreezeNodeLists() {
        List<NodeId> nodes = new ArrayList<>();
        nodes.add(NodeId.of("node-a"));

        CompensationStartedEvent started = new CompensationStartedEvent(
                "event-1",
                RUN_ID,
                TenantId.of("tenant-1"),
                nodes,
                NOW);
        CompensationCompletedEvent completed = new CompensationCompletedEvent(
                "event-2",
                RUN_ID,
                TenantId.of("tenant-1"),
                nodes,
                NOW);

        nodes.add(NodeId.of("node-b"));

        assertEquals(List.of(NodeId.of("node-a")), started.nodesToCompensate());
        assertEquals(List.of(NodeId.of("node-a")), completed.compensatedNodes());
        assertThrows(UnsupportedOperationException.class, () -> started.nodesToCompensate().add(NodeId.of("x")));
        assertThrows(UnsupportedOperationException.class, () -> completed.compensatedNodes().add(NodeId.of("x")));
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
