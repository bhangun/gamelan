package tech.kayys.gamelan.runtime.adapter;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.runtime.ExecutorAdapter;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Adapter for local/in-process Java executors.
 * Provides direct method invocation without RPC overhead.
 */
@ApplicationScoped
public class LocalExecutorAdapter implements ExecutorAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(LocalExecutorAdapter.class);
    private static final String EXECUTOR_TYPE = "local";

    @Override
    public boolean supports(String executorType) {
        return EXECUTOR_TYPE.equals(executorType);
    }

    @Override
    public ExecutorClient adapt(ExecutorClient client) {
        // For local executors, no adaptation needed
        return client;
    }

    @Override
    public CompletionStage<NodeResult> execute(
            NodeContext nodeContext,
            Map<String, Object> variables) {

        LOG.debug("Executing node locally: {}", nodeContext.nodeId());

        // Direct execution - no network overhead
        return Uni.createFrom().<NodeResult>item(() -> {
            // Implementation would invoke the actual executor
            // This is a placeholder that delegates to the client
            throw new UnsupportedOperationException("Direct execution not yet implemented");
        }).subscribeAsCompletionStage();
    }

    @Override
    public String getExecutorType() {
        return EXECUTOR_TYPE;
    }
}
