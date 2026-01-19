package tech.kayys.gamelan.core.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PluginMetricsCollector
 * 
 * Collects and aggregates metrics from plugins
 */
@ApplicationScoped
public class PluginMetricsCollector {
    
    private static final Logger LOG = LoggerFactory.getLogger(PluginMetricsCollector.class);
    
    private final Map<String, PluginMetrics> metricsMap = new ConcurrentHashMap<>();
    
    /**
     * Record plugin execution time
     */
    public void recordExecutionTime(String pluginId, String operation, Duration duration) {
        PluginMetrics metrics = metricsMap.computeIfAbsent(pluginId, PluginMetrics::new);
        metrics.recordExecution(operation, duration);
    }
    
    /**
     * Record plugin error
     */
    public void recordError(String pluginId, String operation, Throwable error) {
        PluginMetrics metrics = metricsMap.computeIfAbsent(pluginId, PluginMetrics::new);
        metrics.recordError(operation);
        LOG.error("Plugin {} error in {}: {}", pluginId, operation, error.getMessage());
    }
    
    /**
     * Get metrics for a plugin
     */
    public PluginMetrics getMetrics(String pluginId) {
        return metricsMap.get(pluginId);
    }
    
    /**
     * Get all plugin metrics
     */
    public Map<String, PluginMetrics> getAllMetrics() {
        return Map.copyOf(metricsMap);
    }
    
    /**
     * Plugin metrics data
     */
    public static class PluginMetrics {
        private final String pluginId;
        private final Map<String, OperationMetrics> operations = new ConcurrentHashMap<>();
        private final Instant startTime = Instant.now();
        
        public PluginMetrics(String pluginId) {
            this.pluginId = pluginId;
        }
        
        void recordExecution(String operation, Duration duration) {
            OperationMetrics opMetrics = operations.computeIfAbsent(
                operation, OperationMetrics::new);
            opMetrics.recordExecution(duration);
        }
        
        void recordError(String operation) {
            OperationMetrics opMetrics = operations.computeIfAbsent(
                operation, OperationMetrics::new);
            opMetrics.recordError();
        }
        
        public String getPluginId() {
            return pluginId;
        }
        
        public Map<String, OperationMetrics> getOperations() {
            return Map.copyOf(operations);
        }
        
        public Duration getUptime() {
            return Duration.between(startTime, Instant.now());
        }
    }
    
    /**
     * Operation-level metrics
     */
    public static class OperationMetrics {
        private final String operation;
        private final AtomicLong executionCount = new AtomicLong();
        private final AtomicLong errorCount = new AtomicLong();
        private final AtomicLong totalDurationMs = new AtomicLong();
        private volatile long minDurationMs = Long.MAX_VALUE;
        private volatile long maxDurationMs = 0;
        
        public OperationMetrics(String operation) {
            this.operation = operation;
        }
        
        void recordExecution(Duration duration) {
            long durationMs = duration.toMillis();
            executionCount.incrementAndGet();
            totalDurationMs.addAndGet(durationMs);
            
            // Update min/max
            if (durationMs < minDurationMs) {
                minDurationMs = durationMs;
            }
            if (durationMs > maxDurationMs) {
                maxDurationMs = durationMs;
            }
        }
        
        void recordError() {
            errorCount.incrementAndGet();
        }
        
        public String getOperation() {
            return operation;
        }
        
        public long getExecutionCount() {
            return executionCount.get();
        }
        
        public long getErrorCount() {
            return errorCount.get();
        }
        
        public double getAverageDurationMs() {
            long count = executionCount.get();
            return count > 0 ? (double) totalDurationMs.get() / count : 0;
        }
        
        public long getMinDurationMs() {
            return minDurationMs == Long.MAX_VALUE ? 0 : minDurationMs;
        }
        
        public long getMaxDurationMs() {
            return maxDurationMs;
        }
        
        public double getErrorRate() {
            long total = executionCount.get();
            return total > 0 ? (double) errorCount.get() / total : 0;
        }
    }
}
