package tech.kayys.gamelan.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

class WeightedSelectionStrategyTest {

    private final WeightedSelectionStrategy strategy = new WeightedSelectionStrategy();

    @Test
    void selectsLowestHeartbeatLoadRelativeToExplicitWeight() {
        ExecutorInfo small = executor("small", Map.of(
                WeightedSelectionStrategy.METADATA_SELECTION_WEIGHT, "2"));
        ExecutorInfo large = executor("large", Map.of(
                WeightedSelectionStrategy.METADATA_SELECTION_WEIGHT, "10"));

        var selected = strategy.select(
                NodeId.of("node-1"),
                List.of(small, large),
                Map.of(WeightedSelectionStrategy.CONTEXT_TASK_COUNTS, Map.of(
                        "small", 1,
                        "large", 3)));

        assertTrue(selected.isPresent());
        assertEquals("large", selected.get().executorId());
    }

    @Test
    void usesMaxConcurrencyAsWeightFallback() {
        ExecutorInfo small = executor("small", Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "2"));
        ExecutorInfo large = executor("large", Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "10"));

        var selected = strategy.select(
                NodeId.of("node-1"),
                List.of(small, large),
                Map.of(WeightedSelectionStrategy.CONTEXT_TASK_COUNTS, Map.of(
                        "small", 1,
                        "large", 3)));

        assertTrue(selected.isPresent());
        assertEquals("large", selected.get().executorId());
    }

    @Test
    void fallsBackToInternalCountsWhenLiveLoadIsUnavailable() {
        ExecutorInfo first = executor("first", Map.of());
        ExecutorInfo second = executor("second", Map.of());

        var selected1 = strategy.select(NodeId.of("node-1"), List.of(first, second), Map.of());
        var selected2 = strategy.select(NodeId.of("node-1"), List.of(first, second), Map.of());

        assertTrue(selected1.isPresent());
        assertTrue(selected2.isPresent());
        assertEquals("first", selected1.get().executorId());
        assertEquals("second", selected2.get().executorId());
    }

    @Test
    void liveLoadDoesNotIncrementFallbackCounts() {
        ExecutorInfo first = executor("first", Map.of());
        ExecutorInfo second = executor("second", Map.of());

        strategy.select(
                NodeId.of("node-1"),
                List.of(first, second),
                Map.of(WeightedSelectionStrategy.CONTEXT_TASK_COUNTS, Map.of(
                        "first", 0,
                        "second", 4)));

        var selected = strategy.select(NodeId.of("node-1"), List.of(first, second), Map.of());

        assertTrue(selected.isPresent());
        assertEquals("first", selected.get().executorId());
    }

    private static ExecutorInfo executor(String executorId, Map<String, String> metadata) {
        return new ExecutorInfo(
                executorId,
                "agent",
                CommunicationType.GRPC,
                "endpoint",
                Duration.ofSeconds(30),
                metadata);
    }
}
