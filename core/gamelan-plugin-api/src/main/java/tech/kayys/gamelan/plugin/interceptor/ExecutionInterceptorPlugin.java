package tech.kayys.gamelan.plugin.interceptor;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Plugin interface for execution interceptors
 * 
 * Execution interceptor plugins can hook into the task execution lifecycle
 * to perform actions before, after, or on error during task execution.
 */
public interface ExecutionInterceptorPlugin extends GamelanPlugin {

    /**
     * Called before a task is executed
     * 
     * @param task the task about to be executed
     * @return a Uni that completes when pre-processing is done
     */
    default Uni<Void> beforeExecution(TaskContext task) {
        return Uni.createFrom().voidItem();
    }

    /**
     * Called after a task is successfully executed
     * 
     * @param task   the task that was executed
     * @param result the execution result
     * @return a Uni that completes when post-processing is done
     */
    default Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
        return Uni.createFrom().voidItem();
    }

    /**
     * Called when a task execution fails
     * 
     * @param task  the task that failed
     * @param error the error that occurred
     * @return a Uni that completes when error handling is done
     */
    default Uni<Void> onError(TaskContext task, Throwable error) {
        return Uni.createFrom().voidItem();
    }

    /**
     * Get the order of this interceptor
     * 
     * Lower order interceptors are executed first.
     * 
     * @return the order (default is 0)
     */
    default int getOrder() {
        return 0;
    }

    /**
     * Task context information
     */
    interface TaskContext {
        String runId();

        String nodeId();

        String nodeType();

        java.util.Map<String, Object> inputs();

        int attempt();
    }

    /**
     * Execution result information
     */
    interface ExecutionResult {
        boolean isSuccess();

        java.util.Map<String, Object> outputs();

        String errorMessage();
    }
}
