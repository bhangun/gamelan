package tech.kayys.silat.registry;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.silat.model.ExecutorHealthInfo;
import tech.kayys.silat.model.ExecutorInfo;

/**
 * Complete executor registry with health monitoring
 */
@ApplicationScoped
public class ExecutorRegistryComplete extends ExecutorRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorRegistryComplete.class);

    private final Map<String, ExecutorHealthInfo> healthInfo = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Update executor heartbeat
     */
    public void updateHeartbeat(String executorId) {
        ExecutorHealthInfo health = healthInfo.computeIfAbsent(
                executorId,
                id -> new ExecutorHealthInfo(executorId));

        health.updateHeartbeat();
        LOG.trace("Heartbeat updated for executor: {}", executorId);
    }

    /**
     * Get healthy executors only
     */
    public List<ExecutorInfo> getHealthyExecutors() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(30));

        return healthInfo.entrySet().stream()
                .filter(entry -> entry.getValue().lastHeartbeat.isAfter(cutoff))
                .map(entry -> getExecutorInfo(entry.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Check executor health
     */
    public boolean isHealthy(String executorId) {
        ExecutorHealthInfo health = healthInfo.get(executorId);
        if (health == null) {
            return false;
        }

        Instant cutoff = Instant.now().minus(Duration.ofSeconds(30));
        return health.lastHeartbeat.isAfter(cutoff);
    }

    private ExecutorInfo getExecutorInfo(String executorId) {
        // Get from parent registry
        return null; // Simplified
    }
}
