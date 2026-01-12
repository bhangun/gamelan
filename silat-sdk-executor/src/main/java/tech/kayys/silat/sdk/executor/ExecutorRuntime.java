package tech.kayys.silat.sdk.executor;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.ErrorInfo;

/**
 * Runtime for managing executor lifecycle
 */
@ApplicationScoped
public class ExecutorRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorRuntime.class);

    private final Map<String, WorkflowExecutor> executors = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final ExecutorTransport transport;
    private volatile boolean running = false;

    @jakarta.inject.Inject
    public ExecutorRuntime(ExecutorTransportFactory transportFactory) {
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.transport = transportFactory.createTransport();
    }

    /**
     * Register an executor
     */
    public void registerExecutor(WorkflowExecutor executor) {
        String type = executor.getExecutorType();
        executors.put(type, executor);
        LOG.info("Registered executor: {}", type);
    }

    /**
     * Start the runtime
     */
    @PostConstruct
    public void start() {
        LOG.info("Starting executor runtime with {} executors", executors.size());
        running = true;

        // Register with engine
        transport.register(new ArrayList<>(executors.values()))
                .subscribe().with(
                        v -> LOG.info("Registered with engine"),
                        error -> LOG.error("Failed to register", error));

        // Start receiving tasks
        transport.receiveTasks()
                .subscribe().with(
                        task -> handleTask(task),
                        error -> LOG.error("Error receiving tasks", error));

        // Start heartbeat
        startHeartbeat();
    }

    /**
     * Stop the runtime
     */
    @PreDestroy
    public void stop() {
        LOG.info("Stopping executor runtime");
        running = false;

        // Unregister from engine
        transport.unregister()
                .subscribe().with(
                        v -> LOG.info("Unregistered from engine"),
                        error -> LOG.error("Failed to unregister", error));

        executorService.shutdown();
    }

    /**
     * Handle incoming task
     */
    private void handleTask(NodeExecutionTask task) {
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
     * Send result back to engine
     */
    private void sendResult(NodeExecutionResult result) {
        LOG.debug("Sending result: run={}, node={}, status={}",
                result.runId().value(), result.nodeId().value(), result.status());

        transport.sendResult(result)
                .subscribe().with(
                        v -> LOG.debug("Result sent successfully"),
                        error -> LOG.error("Failed to send result", error));
    }

    /**
     * Send periodic heartbeat
     */
    private void startHeartbeat() {
        CompletableFuture.runAsync(() -> {
            while (running) {
                try {
                    transport.sendHeartbeat()
                            .subscribe().with(
                                    v -> LOG.trace("Heartbeat sent"),
                                    error -> LOG.warn("Heartbeat failed", error));

                    Thread.sleep(transport.getHeartbeatInterval().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, executorService);
    }
}