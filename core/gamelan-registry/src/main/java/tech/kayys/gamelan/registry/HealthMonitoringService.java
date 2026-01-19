package tech.kayys.gamelan.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.executor.ExecutorHealthInfo;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

/**
 * Health monitoring service for executors
 */
@ApplicationScoped
public class HealthMonitoringService {
    
    private static final Logger LOG = LoggerFactory.getLogger(HealthMonitoringService.class);
    
    // Time threshold for considering an executor unhealthy (30 seconds)
    private static final Duration HEALTH_THRESHOLD = Duration.ofSeconds(30);
    
    @Inject
    ExecutorRegistry executorRegistry;
    
    // Track unhealthy executors
    private final Map<String, Instant> unhealthyExecutors = new ConcurrentHashMap<>();
    
    /**
     * Background job to monitor executor health
     */
    @Scheduled(every = "10s")
    void monitorHealth() {
        LOG.debug("Starting health monitoring cycle");
        
        Uni<List<ExecutorInfo>> executorsUni = executorRegistry.getAllExecutors();
        
        executorsUni.subscribe().with(executors -> {
            Instant threshold = Instant.now().minus(HEALTH_THRESHOLD);
            
            for (ExecutorInfo executor : executors) {
                Uni<ExecutorHealthInfo> healthInfoUni = executorRegistry.getHealthInfo(executor.executorId())
                    .map(opt -> opt.orElse(null));
                
                healthInfoUni.subscribe().with(healthInfo -> {
                    if (healthInfo != null) {
                        boolean isHealthy = healthInfo.lastHeartbeat.isAfter(threshold);
                        
                        if (isHealthy) {
                            // Executor became healthy again
                            if (unhealthyExecutors.containsKey(executor.executorId())) {
                                Instant whenUnhealthy = unhealthyExecutors.remove(executor.executorId());
                                LOG.info("Executor {} is healthy again (was unhealthy for {})", 
                                        executor.executorId(), 
                                        Duration.between(whenUnhealthy, Instant.now()));
                            }
                        } else {
                            // Executor is unhealthy
                            if (!unhealthyExecutors.containsKey(executor.executorId())) {
                                unhealthyExecutors.put(executor.executorId(), Instant.now());
                                LOG.warn("Executor {} is now considered unhealthy (last heartbeat: {})", 
                                        executor.executorId(), 
                                        healthInfo.lastHeartbeat);
                                
                                // Could trigger notifications here
                                notifyUnhealthyExecutor(executor, healthInfo);
                            }
                        }
                    }
                });
            }
        });
    }
    
    /**
     * Notify about unhealthy executor
     */
    private void notifyUnhealthyExecutor(ExecutorInfo executor, ExecutorHealthInfo healthInfo) {
        // In a real implementation, this could send alerts, notifications, etc.
        LOG.warn("Unhealthy executor detected: {} (type: {}, last seen: {})", 
                executor.executorId(), executor.executorType(), healthInfo.lastHeartbeat);
    }
    
    /**
     * Get unhealthy executors
     */
    public Map<String, Instant> getUnhealthyExecutors() {
        return Map.copyOf(unhealthyExecutors);
    }
    
    /**
     * Get health threshold duration
     */
    public Duration getHealthThreshold() {
        return HEALTH_THRESHOLD;
    }
}