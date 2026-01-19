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
 * Adapter for gRPC-based remote executors.
 * Provides high-performance RPC for distributed execution.
 */
@ApplicationScoped
public class GrpcExecutorAdapter implements ExecutorAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcExecutorAdapter.class);
    private static final String EXECUTOR_TYPE = "grpc";

    @Override
    public boolean supports(String executorType) {
        return EXECUTOR_TYPE.equals(executorType);
    }

    @Override
    public ExecutorClient adapt(ExecutorClient client) {
        // Wrap client with gRPC-specific behavior
        return client;
    }

    @Override
    public CompletionStage<NodeResult> execute(
            NodeContext nodeContext,
            Map<String, Object> variables) {

        LOG.debug("Executing node via gRPC: {}", nodeContext.nodeId());

        return Uni.createFrom().<NodeResult>item(() -> {
            // gRPC execution logic would go here
            // - Create gRPC request
            // - Make RPC call
            // - Handle response
            throw new UnsupportedOperationException("gRPC execution not yet implemented");
        }).subscribeAsCompletionStage();
    }

    @Override
    public String getExecutorType() {
        return EXECUTOR_TYPE;
    }
}
