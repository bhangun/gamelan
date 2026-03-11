package tech.kayys.gamelan.sdk.executor.core;

/**
 * Immutable snapshot of executor metadata.
 *
 * Returned by {@link WorkflowExecutor#getExecutorInfo()} for registration
 * payloads, health endpoints, and UI catalog display.
 */
public record ExecutorInfo(
        String executorType,
        String version,
        String description,
        String[] supportedNodeTypes,
        int maxConcurrentTasks) {

    /**
     * Convenience factory for executors that derive metadata from an
     * {@link Executor} annotation.
     */
    public static ExecutorInfo from(WorkflowExecutor executor) {
        return new ExecutorInfo(
                executor.getExecutorType(),
                executor.getVersion(),
                executor.getDescription(),
                executor.getSupportedNodeTypes(),
                executor.getMaxConcurrentTasks());
    }
}
