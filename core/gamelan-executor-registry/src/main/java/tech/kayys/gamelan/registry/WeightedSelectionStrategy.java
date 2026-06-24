package tech.kayys.gamelan.registry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Weighted least-connections executor selection strategy.
 */
public class WeightedSelectionStrategy implements ExecutorSelectionStrategy {

    public static final String CONTEXT_TASK_COUNTS = ExecutorLoadSupport.CONTEXT_TASK_COUNTS;
    public static final String METADATA_SELECTION_WEIGHT = ExecutorLoadSupport.METADATA_SELECTION_WEIGHT;
    public static final String METADATA_EXECUTOR_WEIGHT = ExecutorLoadSupport.METADATA_EXECUTOR_WEIGHT;
    public static final String METADATA_WEIGHT = ExecutorLoadSupport.METADATA_WEIGHT;

    private final Map<String, Integer> fallbackTaskCounts = new ConcurrentHashMap<>();

    @Override
    public Optional<ExecutorInfo> select(
            NodeId nodeId,
            List<ExecutorInfo> availableExecutors,
            Map<String, Object> context) {
        if (availableExecutors == null || availableExecutors.isEmpty()) {
            return Optional.empty();
        }

        Map<String, ?> liveTaskCounts = ExecutorLoadSupport.taskCounts(context);
        boolean hasLiveTaskCounts = ExecutorLoadSupport.hasTaskCountForAnyExecutor(liveTaskCounts, availableExecutors);

        return availableExecutors.stream()
                .min(Comparator
                        .comparingDouble((ExecutorInfo executor) -> loadScore(
                                executor,
                                liveTaskCounts,
                                hasLiveTaskCounts))
                        .thenComparingInt(executor -> taskCount(executor, liveTaskCounts, hasLiveTaskCounts))
                        .thenComparing(Comparator.comparingDouble(ExecutorLoadSupport::weight).reversed())
                        .thenComparing(ExecutorInfo::executorId))
                .map(executor -> {
                    if (!hasLiveTaskCounts) {
                        fallbackTaskCounts.merge(executor.executorId(), 1, Integer::sum);
                    }
                    return executor;
                });
    }

    @Override
    public String getName() {
        return "weighted";
    }

    /**
     * Decrement fallback task count when a task is completed.
     * Live heartbeat task counts remain authoritative when provided by the registry.
     */
    public void decrementTaskCount(String executorId) {
        fallbackTaskCounts.computeIfPresent(executorId, (id, count) -> Math.max(0, count - 1));
    }

    private double loadScore(ExecutorInfo executor, Map<String, ?> liveTaskCounts, boolean hasLiveTaskCounts) {
        return taskCount(executor, liveTaskCounts, hasLiveTaskCounts) / ExecutorLoadSupport.weight(executor);
    }

    private int taskCount(ExecutorInfo executor, Map<String, ?> liveTaskCounts, boolean hasLiveTaskCounts) {
        if (executor == null) {
            return 0;
        }
        if (hasLiveTaskCounts) {
            return ExecutorLoadSupport.taskCount(executor.executorId(), liveTaskCounts);
        }
        return Math.max(0, fallbackTaskCounts.getOrDefault(executor.executorId(), 0));
    }
}
