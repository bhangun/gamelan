package tech.kayys.gamelan.sdk.executor.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Executor metrics
 */
class ExecutorMetrics {

    private final String executorType;
    private final java.util.concurrent.atomic.AtomicLong tasksStarted = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong tasksCompleted = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong tasksFailed = new java.util.concurrent.atomic.AtomicLong();
    private final List<Duration> durations = new CopyOnWriteArrayList<>();

    ExecutorMetrics(String executorType) {
        this.executorType = executorType;
    }

    void recordTaskStarted() {
        tasksStarted.incrementAndGet();
    }

    void recordTaskCompleted(Duration duration) {
        tasksCompleted.incrementAndGet();
        durations.add(duration);
    }

    void recordTaskFailed(Duration duration) {
        tasksFailed.incrementAndGet();
        durations.add(duration);
    }

    public Map<String, Object> getMetrics() {
        return Map.of(
                "executorType", executorType,
                "tasksStarted", tasksStarted.get(),
                "tasksCompleted", tasksCompleted.get(),
                "tasksFailed", tasksFailed.get(),
                "avgDurationMs", calculateAvgDuration());
    }

    private long calculateAvgDuration() {
        if (durations.isEmpty())
            return 0;
        return durations.stream()
                .mapToLong(Duration::toMillis)
                .sum() / durations.size();
    }
}