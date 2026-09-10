package tech.kayys.gamelan.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.gamelan.engine.executor.ExecutorHealthInfo;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

final class ExecutorLoadSupport {

    static final String CONTEXT_TASK_COUNTS = "executorTaskCounts";
    static final String METADATA_MAX_CONCURRENT_TASKS = "gamelan.executor.max-concurrent-tasks";
    static final String METADATA_SELECTION_WEIGHT = "gamelan.executor.selection.weight";
    static final String METADATA_EXECUTOR_WEIGHT = "gamelan.executor.weight";
    static final String METADATA_WEIGHT = "weight";

    private static final double DEFAULT_WEIGHT = 1.0d;

    private ExecutorLoadSupport() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, ?> taskCounts(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Object rawTaskCounts = context.get(CONTEXT_TASK_COUNTS);
        return rawTaskCounts instanceof Map<?, ?> map ? (Map<String, ?>) map : Map.of();
    }

    static boolean hasTaskCountForAnyExecutor(Map<String, ?> taskCounts, List<ExecutorInfo> executors) {
        if (taskCounts == null || taskCounts.isEmpty() || executors == null || executors.isEmpty()) {
            return false;
        }
        return executors.stream()
                .filter(executor -> executor != null)
                .anyMatch(executor -> taskCounts.containsKey(executor.executorId()));
    }

    static int taskCount(String executorId, Map<String, ?> taskCounts) {
        if (executorId == null || taskCounts == null || taskCounts.isEmpty()) {
            return 0;
        }
        return nonNegativeInt(taskCounts.get(executorId));
    }

    static int maxConcurrentTasks(ExecutorInfo executor) {
        return capacityLimit(executor).maxConcurrentTasks();
    }

    static boolean isSaturated(ExecutorInfo executor, ExecutorHealthInfo health) {
        return isSaturated(capacityLimit(executor), health);
    }

    static boolean isSaturated(CapacityLimit capacityLimit, ExecutorHealthInfo health) {
        if (capacityLimit == null || !capacityLimit.valid() || health == null) {
            return false;
        }
        return capacityLimit.maxConcurrentTasks() > 0
                && Math.max(0, health.taskCount) >= capacityLimit.maxConcurrentTasks();
    }

    static CapacityLimit capacityLimit(ExecutorInfo executor) {
        if (executor == null) {
            return CapacityLimit.unspecified();
        }
        if (executor.metadata() == null || executor.metadata().isEmpty()
                || !executor.metadata().containsKey(METADATA_MAX_CONCURRENT_TASKS)) {
            return CapacityLimit.unspecified();
        }
        return positiveInt(executor.metadata().get(METADATA_MAX_CONCURRENT_TASKS))
                .map(CapacityLimit::valid)
                .orElseGet(CapacityLimit::invalid);
    }

    static double weight(ExecutorInfo executor) {
        if (executor == null || executor.metadata() == null || executor.metadata().isEmpty()) {
            return DEFAULT_WEIGHT;
        }
        return positiveDouble(executor.metadata().get(METADATA_SELECTION_WEIGHT))
                .or(() -> positiveDouble(executor.metadata().get(METADATA_EXECUTOR_WEIGHT)))
                .or(() -> positiveDouble(executor.metadata().get(METADATA_WEIGHT)))
                .or(() -> positiveDouble(executor.metadata().get(METADATA_MAX_CONCURRENT_TASKS)))
                .orElse(DEFAULT_WEIGHT);
    }

    private static int nonNegativeInt(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static Optional<Integer> positiveInt(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Double> positiveDouble(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed > 0.0d && Double.isFinite(parsed)
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    record CapacityLimit(boolean declared, boolean valid, int maxConcurrentTasks) {

        private static CapacityLimit unspecified() {
            return new CapacityLimit(false, true, 0);
        }

        private static CapacityLimit invalid() {
            return new CapacityLimit(true, false, 0);
        }

        private static CapacityLimit valid(int maxConcurrentTasks) {
            return new CapacityLimit(true, true, maxConcurrentTasks);
        }
    }
}
