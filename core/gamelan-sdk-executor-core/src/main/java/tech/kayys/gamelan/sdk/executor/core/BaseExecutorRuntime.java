package tech.kayys.gamelan.sdk.executor.core;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.error.ErrorInfo;

/**
 * Base runtime for all executor implementations
 */
public abstract class BaseExecutorRuntime {

    protected static final Logger LOG = LoggerFactory.getLogger(BaseExecutorRuntime.class);

    protected final Map<String, WorkflowExecutor> executors = new ConcurrentHashMap<>();
    protected final ExecutorService executorService;
    protected ExecutorTransport transport;
    protected volatile boolean running = false;

    @Inject
    protected jakarta.enterprise.inject.Instance<WorkflowExecutor> discoveredExecutors;

    public BaseExecutorRuntime() {
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Register an executor manually
     */
    public void registerExecutor(WorkflowExecutor executor) {
        String type = executor.getExecutorType();
        executors.put(type, executor);
        LOG.info("Registered executor: {}", type);
    }

    /**
     * Create transport - implemented by subclasses
     */
    protected abstract ExecutorTransport createTransport();

    /**
     * Initialize runtime - called by subclasses
     */
    protected void initialize() {
        LOG.info("Initializing {} with {} executors", getClass().getSimpleName(), executors.size());
        running = true;

        // Auto-discover and register executors
        if (discoveredExecutors != null) {
            discoveredExecutors.forEach(executor -> {
                String type = executor.getExecutorType();
                executors.put(type, executor);
                LOG.info("Auto-discovered executor: {}", type);
            });
        }
    }

    /**
     * Start the runtime
     */
    @PostConstruct
    public void start() {
        initialize();
        this.transport = createTransport();

        LOG.info("Starting {} with transport: {}", getClass().getSimpleName(),
                transport.getCommunicationType());

        // Start receiving tasks
        transport.receiveTasks()
                .subscribe().with(
                        task -> handleTask(task),
                        error -> LOG.error("Error receiving tasks", error));
    }

    /**
     * Stop the runtime
     */
    @PreDestroy
    public void stop() {
        LOG.info("Stopping {}", getClass().getSimpleName());
        running = false;

        if (transport != null) {
            transport.unregister()
                    .subscribe().with(
                            v -> LOG.info("Unregistered from transport"),
                            error -> LOG.error("Failed to unregister", error));
        }

        executorService.shutdown();
    }

    /**
     * Handle incoming task
     */
    protected void handleTask(NodeExecutionTask task) {
        LOG.debug("Received task: run={}, node={}",
                task.runId().value(), task.nodeId().value());

        // Find appropriate executor
        WorkflowExecutor executor = executors.values().stream()
                .filter(e -> e.canHandle(task))
                .findFirst()
                .orElse(null);

        if (executor == null) {
            LOG.warn("No executor found for task: {}", task.nodeId().value());
            sendResult(SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    new ErrorInfo("NO_EXECUTOR", "No executor found", "", Map.of()),
                    task.token()));
            return;
        }

        // Execute in virtual thread
        executorService.submit(() -> {
            if (executor instanceof AbstractWorkflowExecutor abstractExecutor) {
                abstractExecutor.executeWithLifecycle(task)
                        .subscribe().with(
                                result -> sendResult(result),
                                error -> LOG.error("Execution failed", error));
            } else {
                executor.execute(task)
                        .subscribe().with(
                                result -> sendResult(result),
                                error -> LOG.error("Execution failed", error));
            }
        });
    }

    /**
     * Send result back via transport
     */
    protected void sendResult(NodeExecutionResult result) {
        LOG.debug("Sending result: run={}, node={}, status={}",
                result.runId().value(), result.nodeId().value(), result.status());

        transport.sendResult(result)
                .subscribe().with(
                        v -> LOG.debug("Result sent successfully"),
                        error -> LOG.error("Failed to send result", error));
    }

    /**
     * Get all registered executors
     */
    public Map<String, WorkflowExecutor> getExecutors() {
        return Map.copyOf(executors);
    }

    /**
     * Check if runtime is running
     */
    public boolean isRunning() {
        return running;
    }
}