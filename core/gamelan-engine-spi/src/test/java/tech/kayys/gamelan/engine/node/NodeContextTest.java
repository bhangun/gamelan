package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

class NodeContextTest {

    @Test
    void constructorDefensivelyCopiesAndFreezesInputAndMetadata() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("items", new ArrayList<>(List.of("a")));
        Map<String, Object> input = new HashMap<>();
        input.put("payload", nested);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("profile", "agentic-local");

        NodeContext context = new NodeContext(NodeId.of("node-1"), " task ", input, metadata);

        nested.put("new", "unsafe");
        metadata.put("profile", "mutated");

        assertEquals("task", context.nodeType());
        assertFalse(((Map<?, ?>) context.input().get("payload")).containsKey("new"));
        assertEquals("agentic-local", context.metadata().get("profile"));
        assertThrows(UnsupportedOperationException.class, () -> context.input().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> addToList((List<?>) ((Map<?, ?>) context.input().get("payload")).get("items")));
    }

    @Test
    void constructorNormalizesNullMapsToEmpty() {
        NodeContext context = new NodeContext(NodeId.of("node-1"), "task", null, null);

        assertTrue(context.input().isEmpty());
        assertTrue(context.metadata().isEmpty());
    }

    @Test
    void constructorRejectsMissingIdentity() {
        assertThrows(NullPointerException.class, () -> new NodeContext(null, "task", Map.of(), Map.of()));

        GamelanException exception = assertThrows(
                GamelanException.class,
                () -> new NodeContext(NodeId.of("node-1"), " ", Map.of(), Map.of()));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertTrue(exception.getSafeMessage().contains("nodeType"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void addToList(List<?> list) {
        ((List) list).add("b");
    }
}
