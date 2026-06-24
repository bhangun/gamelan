package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NodeResultTest {

    @Test
    void constructorDefensivelyCopiesAndFreezesOutputAndMetadata() {
        Map<String, Object> output = new HashMap<>();
        output.put("items", new ArrayList<>(List.of("a")));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("trace", Map.of("span", "1"));

        NodeResult result = new NodeResult(true, output, metadata, Instant.EPOCH);

        output.put("unsafe", true);
        metadata.put("trace", "mutated");

        assertFalse(((Map<?, ?>) result.output()).containsKey("unsafe"));
        assertEquals(Map.of("span", "1"), result.metadata().get("trace"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) result.output()).clear());
        assertThrows(UnsupportedOperationException.class, () -> result.metadata().put("x", "y"));
    }

    @Test
    void constructorDefaultsNullMetadataAndCompletedAt() {
        NodeResult result = new NodeResult(true, null, null, null);

        assertTrue(result.metadata().isEmpty());
        assertNotNull(result.completedAt());
    }

    @Test
    void successCreatesSuccessfulResult() {
        NodeResult result = NodeResult.success(Map.of("answer", 42));

        assertTrue(result.success());
        assertEquals(Map.of("answer", 42), result.output());
        assertTrue(result.metadata().isEmpty());
        assertNotNull(result.completedAt());
    }

    @Test
    void failureDefaultsBlankErrorMessage() {
        NodeResult result = NodeResult.failure(" ");

        assertFalse(result.success());
        assertEquals("Node execution failed", result.metadata().get("error"));
        assertNotNull(result.completedAt());
    }
}
