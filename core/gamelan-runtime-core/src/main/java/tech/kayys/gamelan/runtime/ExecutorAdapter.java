package tech.kayys.gamelan.runtime;

import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Adapter interface for different executor types.
 * Allows the runtime to support multiple execution backends (local, HTTP, gRPC,
 * etc.)
 */
public interface ExecutorAdapter {

    /**
     * Check if this adapter supports the given executor type
     */
    boolean supports(String executorType);

    /**
     * Adapt an executor client for use in the runtime
     */
    ExecutorClient adapt(ExecutorClient client);

    /**
     * Execute a task with the given context and variables
     */
    CompletionStage<NodeResult> execute(
            NodeContext nodeContext,
            Map<String, Object> variables);

    /**
     * Get the executor type this adapter handles
     */
    String getExecutorType();
}
