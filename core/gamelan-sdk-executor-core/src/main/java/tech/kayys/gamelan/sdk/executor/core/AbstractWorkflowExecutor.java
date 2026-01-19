package tech.kayys.gamelan.sdk.executor.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.error.ErrorInfo;

/**
 * Abstract base class for executors with common functionality
 */
public abstract class AbstractWorkflowExecutor implements WorkflowExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractWorkflowExecutor.class);

    protected final String executorType;
    protected final ExecutorConfig config;
    protected final ExecutorMetrics metrics;
    protected final AtomicInteger activeTaskCount = new AtomicInteger(0);

    protected AbstractWorkflowExecutor() {
        // Extract executor type from annotation
        Executor annotation = getClass().getAnnotation(Executor.class);

        // Handle Quarkus/CDI proxies
        if (annotation == null && getClass().getName().endsWith("_Subclass")) {
            annotation = getClass().getSuperclass().getAnnotation(Executor.class);
        }

        if (annotation == null) {
            LOG.error("Failed to find @Executor annotation on class: {}", getClass().getName());
            // Attempt to look up the hierarchy
            Class<?> current = getClass();
            while (current != Object.class) {
                annotation = current.getAnnotation(Executor.class);
                if (annotation != null) {
                    LOG.info("Found @Executor annotation on parent class: {}", current.getName());
                    break;
                }
                current = current.getSuperclass();
            }
        }

        if (annotation == null) {
            throw new IllegalStateException(
                    "Executor class must be annotated with @Executor: " + getClass().getName());
        }

        this.executorType = annotation.executorType();
        this.config = new ExecutorConfig(
                annotation.maxConcurrentTasks(),
                Arrays.asList(annotation.supportedNodeTypes()),
                annotation.communicationType(),
                SecurityConfig.disabled());
        this.metrics = new ExecutorMetrics(executorType);
    }

    @Override
    public final String getExecutorType() {
        return executorType;
    }

    @Override
    public int getMaxConcurrentTasks() {
        return config.maxConcurrentTasks();
    }

    @Override
    public String[] getSupportedNodeTypes() {
        return config.supportedNodeTypes().toArray(new String[0]);
    }

    @Override
    public boolean isReady() {
        return activeTaskCount.get() < getMaxConcurrentTasks();
    }

    /**
     * Execute with comprehensive lifecycle hooks and error handling
     */
    public final Uni<NodeExecutionResult> executeWithLifecycle(NodeExecutionTask task) {
        LOG.debug("Executing task: run={}, node={}, attempt={}, executor={}",
                task.runId().value(), task.nodeId().value(), task.attempt(), executorType);

        // Check if executor is ready to handle the task
        if (!isReady()) {
            LOG.warn("Executor {} is not ready, active tasks: {}, max: {}",
                    executorType, activeTaskCount.get(), getMaxConcurrentTasks());
            return Uni.createFrom().item(SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    ErrorInfo.of(new IllegalStateException("Executor not ready - too many active tasks")),
                    task.token()));
        }

        Instant startTime = Instant.now();
        activeTaskCount.incrementAndGet();
        metrics.recordTaskStarted();

        return beforeExecute(task)
                .onItem().invoke(() -> LOG.trace("Before execute completed for task: {}", task.nodeId()))
                .onFailure()
                .invoke(throwable -> LOG.error("Before execute failed for task: {}", task.nodeId(), throwable))
                .flatMap(v -> execute(task))
                .onItem().invoke(result -> {
                    Duration duration = Duration.between(startTime, Instant.now());
                    activeTaskCount.decrementAndGet();
                    metrics.recordTaskCompleted(duration);
                    LOG.info("Task completed: run={}, node={}, status={}, duration={}ms, attempt={}",
                            task.runId().value(), task.nodeId().value(),
                            result.status(), duration.toMillis(), task.attempt());
                })
                .onFailure().invoke(throwable -> {
                    Duration duration = Duration.between(startTime, Instant.now());
                    activeTaskCount.decrementAndGet();
                    metrics.recordTaskFailed(duration);
                    LOG.error("Task failed: run={}, node={}, attempt={}",
                            task.runId().value(), task.nodeId().value(), task.attempt(), throwable);

                    // Call onError hook for error handling
                    onError(task, throwable)
                            .subscribe().with(
                                    ignored -> LOG.trace("onError hook completed for task: {}", task.nodeId()),
                                    error -> LOG.warn("onError hook failed for task: {}", task.nodeId(), error));
                })
                .onFailure().recoverWithItem(throwable -> {
                    Duration duration = Duration.between(startTime, Instant.now());
                    activeTaskCount.decrementAndGet();
                    metrics.recordTaskFailed(duration);
                    LOG.warn("Recovering from execution failure for task: {}", task.nodeId(), throwable);
                    return SimpleNodeExecutionResult.failure(
                            task.runId(),
                            task.nodeId(),
                            task.attempt(),
                            ErrorInfo.of(throwable),
                            task.token());
                })
                .flatMap(result -> afterExecute(task, result)
                        .onItem().invoke(() -> LOG.trace("After execute completed for task: {}", task.nodeId()))
                        .onFailure()
                        .invoke(throwable -> LOG.warn("After execute failed for task: {}", task.nodeId(), throwable))
                        .replaceWith(result));
    }

    /**
     * Validates if the executor can handle the given task
     */
    @Override
    public boolean canHandle(NodeExecutionTask task) {
        // Check if the node type is supported
        String[] supportedTypes = getSupportedNodeTypes();
        if (supportedTypes.length > 0) {
            String nodeType = extractNodeType(task);
            for (String supportedType : supportedTypes) {
                if (supportedType.equals(nodeType)) {
                    return true;
                }
            }
            LOG.debug("Executor {} cannot handle node type: {} for task: {}",
                    executorType, nodeType, task.nodeId());
            return false;
        }
        return true; // If no specific types defined, assume it can handle any
    }

    /**
     * Extracts node type from the task (implementation may vary based on actual
     * task structure)
     */
    protected String extractNodeType(NodeExecutionTask task) {
        // Look for the special __node_type__ key in the context, which is the system
        // convention
        if (task.context() != null && task.context().containsKey("__node_type__")) {
            return String.valueOf(task.context().get("__node_type__"));
        }

        // Fallback to node ID value if not found
        return task.nodeId().value();
    }

    /**
     * Gets the current number of active tasks
     */
    public int getActiveTaskCount() {
        return activeTaskCount.get();
    }

    /**
     * Gets the configuration for this executor
     */
    public ExecutorConfig getConfig() {
        return config;
    }

    /**
     * Gets the metrics for this executor
     */
    public ExecutorMetrics getMetrics() {
        return metrics;
    }
}
