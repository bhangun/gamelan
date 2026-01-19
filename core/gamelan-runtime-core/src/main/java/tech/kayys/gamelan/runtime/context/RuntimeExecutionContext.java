package tech.kayys.gamelan.runtime.context;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provides runtime-specific execution context.
 * Manages thread pools, resource limits, and timeouts.
 */
@ApplicationScoped
public class RuntimeExecutionContext {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeExecutionContext.class);

    private final ExecutorService executorService;
    private final Map<String, Object> attributes;
    private final Duration defaultTimeout;
    private final int maxConcurrentTasks;

    public RuntimeExecutionContext() {
        this.maxConcurrentTasks = Runtime.getRuntime().availableProcessors() * 2;
        this.executorService = Executors.newFixedThreadPool(maxConcurrentTasks);
        this.attributes = new ConcurrentHashMap<>();
        this.defaultTimeout = Duration.ofMinutes(5);

        LOG.info("RuntimeExecutionContext initialized with {} threads", maxConcurrentTasks);
    }

    /**
     * Get the executor service for async task execution
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Get a context attribute
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Set a context attribute
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Get the default timeout for task execution
     */
    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    /**
     * Get the maximum number of concurrent tasks
     */
    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    /**
     * Shutdown the execution context
     */
    public void shutdown() {
        LOG.info("Shutting down RuntimeExecutionContext");
        executorService.shutdown();
    }
}
