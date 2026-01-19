package tech.kayys.gamelan.dispatcher;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

/**
 * Contract for dispatching tasks to executors
 */
public interface TaskDispatcher {

    /**
     * Dispatch a task to an executor
     *
     * @param task the task to dispatch
     * @param executor the executor to dispatch to
     * @return a Uni that completes when the task is dispatched
     */
    Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor);

    /**
     * Check if this dispatcher supports the given executor
     *
     * @param executor the executor information
     * @return true if this dispatcher can handle the executor
     */
    default boolean supports(ExecutorInfo executor) {
        return true; // Default implementation assumes support for all executors
    }

    /**
     * Health check for the dispatcher
     *
     * @return Uni that completes if healthy, fails if unhealthy
     */
    default Uni<Boolean> isHealthy() {
        return Uni.createFrom().item(true);
    }

    /**
     * Cancel a previously dispatched task
     *
     * @param task the task to cancel
     * @return Uni that completes when cancellation is processed
     */
    default Uni<Void> cancel(NodeExecutionTask task) {
        return Uni.createFrom().voidItem(); // Default no-op implementation
    }

    /**
     * Get the priority of this dispatcher
     * Higher priority dispatchers are preferred when multiple dispatchers
     * support the same executor.
     *
     * @return the priority (default is 0)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Register an additional dispatcher (for plugin support)
     * This is a default no-op implementation; concrete implementations
     * may provide actual registration capabilities
     *
     * @param dispatcher the dispatcher to register
     */
    default void registerDispatcher(TaskDispatcher dispatcher) {
        // Default no-op implementation
    }

}
