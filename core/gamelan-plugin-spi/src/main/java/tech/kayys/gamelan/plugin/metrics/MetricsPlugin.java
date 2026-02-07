package tech.kayys.gamelan.plugin.metrics;

import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

import java.util.Map;

/**
 * MetricsPlugin
 * 
 * Provides metrics collection capabilities for plugins.
 * Plugins can implement this interface to expose custom metrics.
 */
public interface MetricsPlugin extends GamelanPlugin {
    
    /**
     * Get plugin metrics
     * 
     * @return Map of metric name to metric value
     */
    Map<String, Object> getMetrics();
    
    /**
     * Get plugin health status
     */
    HealthStatus getHealthStatus();
    
    /**
     * Health status
     */
    enum HealthStatus {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }
    
    /**
     * Metric metadata
     */
    record MetricInfo(
        String name,
        String description,
        MetricType type,
        String unit
    ) {}
    
    /**
     * Metric types
     */
    enum MetricType {
        COUNTER,
        GAUGE,
        HISTOGRAM,
        TIMER
    }
}
