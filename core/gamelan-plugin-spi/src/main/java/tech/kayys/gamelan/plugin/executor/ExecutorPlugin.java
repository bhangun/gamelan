package tech.kayys.gamelan.plugin.executor;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Plugin interface for custom task executors
 * 
 * Executor plugins allow dynamic loading of custom task handlers
 * without modifying the core executor runtime.
 * 
 * Example usage:
 * 
 * <pre>
 * {@code
 * public class HttpExecutorPlugin implements ExecutorPlugin {
 *     public String getExecutorType() {
 *         return "http";
 *     }
 * 
 *     public boolean canHandle(NodeExecutionTask task) {
 *         return task.taskType().equals("http-request");
 *     }
 * 
 *     public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
 *         // Execute HTTP request
 *     }
 * }
 * }
 * </pre>
 */
public interface ExecutorPlugin extends GamelanPlugin {

    /**
     * Get the executor type this plugin handles
     * 
     * @return executor type (e.g., "http", "database", "ml-inference")
     */
    String getExecutorType();

    /**
     * Check if this plugin can handle the given task
     * 
     * @param task the task to check
     * @return true if this plugin can handle the task
     */
    boolean canHandle(NodeExecutionTask task);

    /**
     * Execute the task
     * 
     * @param task the task to execute
     * @return execution result wrapped in Uni for reactive execution
     */
    Uni<NodeExecutionResult> execute(NodeExecutionTask task);

    /**
     * Get plugin priority (higher = preferred when multiple plugins can handle a
     * task)
     * 
     * @return priority value, default is 0
     */
    default int getPriority() {
        return 0;
    }
}
