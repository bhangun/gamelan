package tech.kayys.silat.model;

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
        this.lastHeartbeat = Instant.now();
    }
}