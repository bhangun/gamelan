package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NodeExecutionRecordTest {

    @Test
    void constructorDefensivelyCopiesAndFreezesPayloadMaps() {
        Map<String, Object> inputs = nestedPayload("prompt", "plan");
        Map<String, Object> outputs = nestedPayload("answer", 42);
        Map<String, Object> metadata = nestedPayload("nodeType", "AGENT_LOOP");

        NodeExecutionRecord record = NodeExecutionRecord.builder()
                .nodeId("node-1")
                .status(NodeExecutionStatus.COMPLETED)
                .startedAt(Instant.EPOCH)
                .completedAt(Instant.EPOCH.plusMillis(10))
                .duration(Duration.ofMillis(10))
                .inputs(inputs)
                .outputs(outputs)
                .metadata(metadata)
                .attempt(1)
                .build();

        assertFrozenNestedPayload(record.getInputs(), inputs, "prompt", "plan");
        assertFrozenNestedPayload(record.getOutputs(), outputs, "answer", 42);
        assertFrozenNestedPayload(record.getMetadata(), metadata, "nodeType", "AGENT_LOOP");
    }

    @Test
    void constructorNormalizesNullPayloadMapsToEmptyMaps() {
        NodeExecutionRecord record = NodeExecutionRecord.builder()
                .nodeId("node-1")
                .status(NodeExecutionStatus.COMPLETED)
                .build();

        assertFalse(record.getInputs().containsKey("x"));
        assertFalse(record.getOutputs().containsKey("x"));
        assertFalse(record.getMetadata().containsKey("x"));
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
            Map<String, Object> recordPayload,
            Map<String, Object> callerPayload,
            String key,
            Object expectedValue) {

        Map<String, Object> callerNested = (Map<String, Object>) callerPayload.get("nested");
        callerPayload.put("late", "ignored");
        callerNested.put(key, "mutated");

        Map<String, Object> recordNested = (Map<String, Object>) recordPayload.get("nested");
        assertEquals(expectedValue, recordNested.get(key));
        assertFalse(recordPayload.containsKey("late"));
        assertThrows(UnsupportedOperationException.class, () -> recordPayload.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> recordNested.put("x", "y"));
    }
}
