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
 * Adapter for HTTP-based remote executors.
 * Provides REST API integration for task execution.
 */
@ApplicationScoped
public class HttpExecutorAdapter implements ExecutorAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(HttpExecutorAdapter.class);
    private static final String EXECUTOR_TYPE = "http";

    @Override
    public boolean supports(String executorType) {
        return EXECUTOR_TYPE.equals(executorType);
    }

    @Override
    public ExecutorClient adapt(ExecutorClient client) {
        // Wrap client with HTTP-specific behavior
        return client;
    }

    @Override
    public CompletionStage<NodeResult> execute(
            NodeContext nodeContext,
            Map<String, Object> variables) {

        LOG.debug("Executing node via HTTP: {}", nodeContext.nodeId());

        return Uni.createFrom().<NodeResult>item(() -> {
            // HTTP execution logic would go here
            // - Serialize request
            // - Make HTTP call
            // - Deserialize response
            throw new UnsupportedOperationException("HTTP execution not yet implemented");
        }).subscribeAsCompletionStage();
    }

    @Override
    public String getExecutorType() {
        return EXECUTOR_TYPE;
    }
}
