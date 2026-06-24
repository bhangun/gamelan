package tech.kayys.gamelan.sdk.executor.core;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.execution.ExecutionContext;
import tech.kayys.gamelan.engine.execution.ExecutionError;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.run.WaitInfo;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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

        WorkflowExecutor executor = selectExecutor(task);

        if (executor == null) {
            LOG.warn("No executor found for task: {}", task.nodeId().value());
            sendResult(task, SimpleNodeExecutionResult.failure(
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
                                result -> sendResult(task, result),
                                error -> LOG.error("Execution failed", error));
            } else {
                executor.execute(task)
                        .subscribe().with(
                                result -> sendResult(task, result),
                                error -> LOG.error("Execution failed", error));
            }
        });
    }

    protected void sendResult(NodeExecutionTask task, NodeExecutionResult result) {
        sendResult(enrichResultWithTaskContext(task, result));
    }

    protected WorkflowExecutor selectExecutor(NodeExecutionTask task) {
        String requestedExecutorType = requestedExecutorType(task);
        if (requestedExecutorType != null) {
            WorkflowExecutor exactExecutor = executors.get(requestedExecutorType);
            if (exactExecutor != null && exactExecutor.canHandle(task)) {
                return exactExecutor;
            }
        }

        return executors.values().stream()
                .filter(e -> e.isReady() && e.canHandle(task))
                .findFirst()
                .or(() -> executors.values().stream()
                        .filter(e -> e.canHandle(task))
                        .findFirst())
                .orElse(null);
    }

    private String requestedExecutorType(NodeExecutionTask task) {
        if (task == null || task.context() == null) {
            return null;
        }

        Object value = task.context().get(NodeExecutionTask.NODE_TYPE_KEY);
        if (value == null) {
            return null;
        }

        String executorType = String.valueOf(value).trim();
        return executorType.isBlank() ? null : executorType;
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

    private NodeExecutionResult enrichResultWithTaskContext(NodeExecutionTask task, NodeExecutionResult result) {
        if (task == null || result == null || task.context() == null) {
            return result;
        }

        String tenantId = contextText(task.context().get(NodeExecutionTask.TENANT_ID_KEY));
        if (tenantId == null) {
            return result;
        }

        Map<String, Object> metadata = new HashMap<>();
        if (result.getMetadata() != null) {
            metadata.putAll(result.getMetadata());
        }
        metadata.put(NodeExecutionTask.TENANT_ID_KEY, tenantId);
        metadata.put("tenantId", tenantId);
        return new TaskContextNodeExecutionResult(result, Map.copyOf(metadata));
    }

    private String contextText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
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

    private static final class TaskContextNodeExecutionResult implements NodeExecutionResult {
        private final NodeExecutionResult delegate;
        private final Map<String, Object> metadata;

        private TaskContextNodeExecutionResult(NodeExecutionResult delegate, Map<String, Object> metadata) {
            this.delegate = delegate;
            this.metadata = metadata;
        }

        @Override
        public WorkflowRunId runId() {
            return delegate.runId();
        }

        @Override
        public NodeId nodeId() {
            return delegate.nodeId();
        }

        @Override
        public int attempt() {
            return delegate.attempt();
        }

        @Override
        public NodeExecutionStatus status() {
            return delegate.status();
        }

        @Override
        public Map<String, Object> output() {
            return delegate.output();
        }

        @Override
        public ErrorInfo error() {
            return delegate.error();
        }

        @Override
        public ExecutionToken executionToken() {
            return delegate.executionToken();
        }

        @Override
        public NodeExecutionStatus getStatus() {
            return delegate.getStatus();
        }

        @Override
        public String getNodeId() {
            return delegate.getNodeId();
        }

        @Override
        public Instant getExecutedAt() {
            return delegate.getExecutedAt();
        }

        @Override
        public Duration getDuration() {
            return delegate.getDuration();
        }

        @Override
        public ExecutionContext getUpdatedContext() {
            return delegate.getUpdatedContext();
        }

        @Override
        public ExecutionError getError() {
            return delegate.getError();
        }

        @Override
        public WaitInfo getWaitInfo() {
            return delegate.getWaitInfo();
        }

        @Override
        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
