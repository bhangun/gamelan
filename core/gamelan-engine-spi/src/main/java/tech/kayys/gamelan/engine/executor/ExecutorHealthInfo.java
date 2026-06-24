package tech.kayys.gamelan.engine.executor;

import java.time.Instant;

/**
 * Executor health info
 */
public class ExecutorHealthInfo {
    public final String executorId;
    public Instant lastHeartbeat;
    public Instant registeredAt;
    public int taskCount;

    public ExecutorHealthInfo(String executorId) {
        this.executorId = executorId;
        this.lastHeartbeat = Instant.now();
        this.registeredAt = Instant.now();
        this.taskCount = 0;
    }

    public void updateHeartbeat() {
        updateHeartbeat(taskCount);
    }

    public void updateHeartbeat(int taskCount) {
        this.lastHeartbeat = Instant.now();
        this.taskCount = Math.max(0, taskCount);
    }
}
