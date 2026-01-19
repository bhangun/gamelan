package tech.kayys.gamelan.sdk.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.sdk.executor.core.BaseExecutorRuntime;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

import java.time.Duration;

/**
 * Local executor runtime for same-JVM execution
 * Uses Vert.x EventBus for communication
 */
@Startup
@ApplicationScoped
public class LocalExecutorRuntime extends BaseExecutorRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(LocalExecutorRuntime.class);

    @Inject
    LocalExecutorTransportFactory transportFactory;

    @Override
    protected ExecutorTransport createTransport() {
        return transportFactory.createTransport();
    }

    @Override
    protected void initialize() {
        super.initialize();

        LOG.info("Initializing Local Executor Runtime");
        LOG.info("Running in local mode - no remote registration needed");

        // For local mode, we can immediately start without registration
        // Executors are discovered via CDI injection
    }

    /**
     * Start the local runtime
     */
    @PostConstruct
    @Override
    public void start() {
        super.start();

        // Local runtime doesn't need explicit registration
        // Executors are available immediately via CDI
        LOG.info("Local Executor Runtime started successfully");
        LOG.info("Registered executors: {}", executors.keySet());
    }

    /**
     * Stop the local runtime
     */
    @PreDestroy
    @Override
    public void stop() {
        LOG.info("Stopping Local Executor Runtime");

        // For local mode, we just clean up resources
        // No need to unregister from remote engine

        super.stop();
    }

    /**
     * Additional local-specific methods
     */

    /**
     * Get executor by type
     */
    public WorkflowExecutor getExecutor(String executorType) {
        return executors.get(executorType);
    }

    /**
     * Check if executor type is available
     */
    public boolean hasExecutor(String executorType) {
        return executors.containsKey(executorType);
    }

    /**
     * Execute task synchronously (useful for testing)
     */
    public tech.kayys.gamelan.engine.node.NodeExecutionResult executeTaskSync(
            tech.kayys.gamelan.engine.node.NodeExecutionTask task) {

        WorkflowExecutor executor = executors.values().stream()
                .filter(e -> e.canHandle(task))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No executor found for task: " + task.nodeId()));

        return executor.execute(task)
                .await().atMost(Duration.ofSeconds(30));
    }
}