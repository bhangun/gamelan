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

class LeastLoadedSelectionStrategyTest {

    private final LeastLoadedSelectionStrategy strategy = new LeastLoadedSelectionStrategy();

    @Test
    void selectsExecutorWithLowestHeartbeatTaskCount() {
        ExecutorInfo busy = executor("busy", Map.of());
        ExecutorInfo idle = executor("idle", Map.of());

        var selected = strategy.select(
                NodeId.of("node-1"),
                List.of(busy, idle),
                Map.of(LeastLoadedSelectionStrategy.CONTEXT_TASK_COUNTS, Map.of(
                        "busy", 7,
                        "idle", 1)));

        assertTrue(selected.isPresent());
        assertEquals("idle", selected.get().executorId());
    }

    @Test
    void prefersLowerUtilizationWhenMaxConcurrencyIsKnown() {
        ExecutorInfo small = executor("small", Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "2"));
        ExecutorInfo large = executor("large", Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "10"));

        var selected = strategy.select(
                NodeId.of("node-1"),
                List.of(small, large),
                Map.of(LeastLoadedSelectionStrategy.CONTEXT_TASK_COUNTS, Map.of(
                        "small", 1,
                        "large", 2)));

        assertTrue(selected.isPresent());
        assertEquals("large", selected.get().executorId());
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
