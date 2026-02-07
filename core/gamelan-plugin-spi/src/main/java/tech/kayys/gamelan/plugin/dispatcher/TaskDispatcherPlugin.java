package tech.kayys.gamelan.plugin.dispatcher;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Plugin interface for custom task dispatchers
 * 
 * Task dispatcher plugins can handle dispatching tasks to executors
 * using custom communication protocols beyond the built-in GRPC, Kafka, and
 * REST.
 */
public interface TaskDispatcherPlugin extends GamelanPlugin {

    /**
     * Check if this dispatcher supports the given executor
     * 
     * @param executor the executor information
     * @return true if this dispatcher can handle the executor
     */
    boolean supports(ExecutorInfo executor);

    /**
     * Dispatch a task to an executor
     * 
     * @param task     the task to dispatch
     * @param executor the executor to dispatch to
     * @return a Uni that completes when the task is dispatched
     */
    Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor);

    /**
     * Get the priority of this dispatcher
     * 
     * Higher priority dispatchers are preferred when multiple dispatchers
     * support the same executor.
     * 
     * @return the priority (default is 0)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Executor information needed for dispatching
     */
    interface ExecutorInfo {
        String executorId();

        String executorType();

        String communicationType();

        String endpoint();

        int timeout();
    }

    /**
     * Node execution task information
     */
    /*
     * interface NodeExecutionTask {
     * String runId();
     * 
     * String nodeId();
     * 
     * String nodeType();
     * 
     * java.util.Map<String, Object> inputs();
     * 
     * int attempt();
     * }
     */
}
