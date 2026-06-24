package tech.kayys.gamelan.registry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Selects the executor with the lowest current heartbeat-reported load.
 */
public class LeastLoadedSelectionStrategy implements ExecutorSelectionStrategy {

    public static final String CONTEXT_TASK_COUNTS = ExecutorLoadSupport.CONTEXT_TASK_COUNTS;
    public static final String METADATA_MAX_CONCURRENT_TASKS = ExecutorLoadSupport.METADATA_MAX_CONCURRENT_TASKS;

    @Override
    public Optional<ExecutorInfo> select(
            NodeId nodeId,
            List<ExecutorInfo> availableExecutors,
            Map<String, Object> context) {

        if (availableExecutors == null || availableExecutors.isEmpty()) {
            return Optional.empty();
        }

        Map<String, ?> taskCounts = ExecutorLoadSupport.taskCounts(context);
        return availableExecutors.stream()
                .min(Comparator
                        .comparingDouble((ExecutorInfo executor) -> utilizationScore(executor, taskCounts))
                        .thenComparingInt(executor -> ExecutorLoadSupport.taskCount(executor.executorId(), taskCounts))
                        .thenComparing(ExecutorInfo::executorId));
    }

    @Override
    public String getName() {
        return "least-loaded";
    }

    private double utilizationScore(ExecutorInfo executor, Map<String, ?> taskCounts) {
        int currentTasks = ExecutorLoadSupport.taskCount(executor.executorId(), taskCounts);
        int maxConcurrentTasks = ExecutorLoadSupport.maxConcurrentTasks(executor);
        return maxConcurrentTasks > 0 ? (double) currentTasks / maxConcurrentTasks : currentTasks;
    }
}
