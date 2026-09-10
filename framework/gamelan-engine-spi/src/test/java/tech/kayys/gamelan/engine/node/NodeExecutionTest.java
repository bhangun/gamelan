package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NodeExecutionTest {

    @Test
    @SuppressWarnings("unchecked")
    void completeDefensivelyCopiesAndFreezesNestedOutput() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("answer", 42);
        Map<String, Object> output = new HashMap<>();
        output.put("nested", nested);

        NodeExecution execution = NodeExecution.create(NodeId.of("node-1"), taskNode());
        execution.complete(output);

        output.put("late", "ignored");
        nested.put("answer", 99);

        assertEquals(42, ((Map<String, Object>) execution.getOutput().get("nested")).get("answer"));
        assertThrows(UnsupportedOperationException.class, () -> execution.getOutput().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) execution.getOutput().get("nested")).put("x", "y"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void setOutputDefensivelyCopiesAndFreezesNestedOutput() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("topic", "orders");
        Map<String, Object> output = new HashMap<>();
        output.put("nested", nested);

        NodeExecution execution = NodeExecution.create(NodeId.of("node-1"), taskNode());
        execution.setOutput(output);

        output.put("late", "ignored");
        nested.put("topic", "mutated");

        assertEquals("orders", ((Map<String, Object>) execution.getOutput().get("nested")).get("topic"));
        assertThrows(UnsupportedOperationException.class, () -> execution.getOutput().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) execution.getOutput().get("nested")).put("x", "y"));
    }

    @Test
    void copyOfDoesNotShareMutableNodeState() {
        NodeExecution original = NodeExecution.create(NodeId.of("node-1"), taskNode());
        original.complete(Map.of("result", "ok"));

        NodeExecution copy = NodeExecution.copyOf(original);
        copy.setStatus(NodeExecutionStatus.FAILED);
        copy.setOutput(Map.of("result", "mutated"));

        assertEquals(NodeExecutionStatus.COMPLETED, original.getStatus());
        assertEquals("ok", original.getOutput().get("result"));
    }

    private static NodeDefinition taskNode() {
        return NodeDefinition.builder()
                .id(NodeId.of("node-1"))
                .type(NodeType.TASK)
                .build();
    }
}
