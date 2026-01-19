package tech.kayys.gamelan.sdk.executor;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

/**
 * Base interface for all workflow executors
 */
public interface WorkflowExecutor {

    /**
     * Execute a node task
     *
     * @param task The task to execute
     * @return Result of execution
     */
    Uni<NodeExecutionResult> execute(NodeExecutionTask task);

    /**
     * Get executor type
     */
    String getExecutorType();

    /**
     * Validate if this executor can handle the task
     */
    default boolean canHandle(NodeExecutionTask task) {
        return true;
    }

    /**
     * Get the maximum number of concurrent tasks this executor can handle
     */
    default int getMaxConcurrentTasks() {
        return Integer.MAX_VALUE; // Unlimited by default
    }

    /**
     * Called before execution starts
     */
    default Uni<Void> beforeExecute(NodeExecutionTask task) {
        return Uni.createFrom().voidItem();
    }

    /**
     * Called after execution completes (success or failure)
     */
    default Uni<Void> afterExecute(
            NodeExecutionTask task,
            NodeExecutionResult result) {
        return Uni.createFrom().voidItem();
    }

    /**
     * Called when execution fails with an exception
     */
    default Uni<Void> onError(NodeExecutionTask task, Throwable error) {
        return Uni.createFrom().voidItem();
    }

    /**
     * Get supported node types that this executor can handle
     */
    default String[] getSupportedNodeTypes() {
        return new String[0]; // Empty array means all types supported
    }

    /**
     * Check if the executor is ready to accept new tasks
     */
    default boolean isReady() {
        return true;
    }

    /**
     * Initialize the executor (called during registration)
     */
    default Uni<Void> initialize() {
        return Uni.createFrom().voidItem();
    }

    /**
     * Cleanup the executor (called during unregistration)
     */
    default Uni<Void> cleanup() {
        return Uni.createFrom().voidItem();
    }
}