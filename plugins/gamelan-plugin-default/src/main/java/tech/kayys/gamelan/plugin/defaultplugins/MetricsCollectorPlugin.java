package tech.kayys.gamelan.plugin.defaultplugins;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginException;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

/**
 * Default metrics collector plugin that tracks workflow execution metrics
 */
public class MetricsCollectorPlugin implements ExecutionInterceptorPlugin {

    private PluginContext context;
    private Logger logger;
    private volatile boolean started = false;

    // Metrics storage
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> timers = new ConcurrentHashMap<>();

    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        this.logger = context.getLogger();
        logger.info("Metrics Collector Plugin initialized");
    }

    public void start() throws PluginException {
        started = true;
        logger.info("Metrics Collector Plugin started");
    }

    public void stop() throws PluginException {
        started = false;
        logger.info("Metrics Collector Plugin stopped");
    }

    public PluginMetadata getMetadata() {
        return new PluginMetadata(
                "metrics-collector",
                "Metrics Collector Plugin",
                "1.0.0",
                "Gamelan Team",
                "Collects workflow execution metrics",
                null,
                null);
    }

    @Override
    public Uni<Void> beforeExecution(TaskContext task) {
        if (started) {
            String metricKey = "node_execution_time_" + task.nodeId();
            timers.put(metricKey + "_start_" + System.nanoTime(), System.currentTimeMillis());
            incrementCounter("node_executions_started");
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
        if (started) {
            String metricKey = "node_execution_time_" + task.nodeId();
            // Find the start time for this execution
            String prefix = metricKey + "_start_";
            timers.entrySet().removeIf(entry -> {
                if (entry.getKey().startsWith(prefix)) {
                    long duration = System.currentTimeMillis() - entry.getValue();
                    incrementCounter("node_execution_duration_ms", duration);
                    return true; // Remove the start marker
                }
                return false;
            });
            incrementCounter("node_executions_completed");
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> onError(TaskContext task, Throwable error) {
        if (started) {
            incrementCounter("node_execution_errors");
        }
        return Uni.createFrom().voidItem();
    }

    private void incrementCounter(String counterName) {
        counters.computeIfAbsent(counterName, k -> new AtomicLong(0)).incrementAndGet();
    }

    private void incrementCounter(String counterName, long value) {
        counters.computeIfAbsent(counterName, k -> new AtomicLong(0)).addAndGet(value);
    }

    public Map<String, Long> getMetrics() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }
}