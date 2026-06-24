package tech.kayys.gamelan.runtime.adapter;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.runtime.ExecutorAdapter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

/**
 * Adapter for local/in-process Java executors.
 * Provides direct method invocation without RPC overhead.
 */
@ApplicationScoped
public class LocalExecutorAdapter implements ExecutorAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(LocalExecutorAdapter.class);
    private static final String EXECUTOR_TYPE = "local";
    private final Map<String, ExecutorClient> clients = new ConcurrentHashMap<>();

    @Override
    public boolean supports(String executorType) {
        return EXECUTOR_TYPE.equals(executorType);
    }

    @Override
    public ExecutorClient adapt(ExecutorClient client) {
        Objects.requireNonNull(client, "ExecutorClient cannot be null");
        String clientType = requireExecutorType(client.executorType());
        ExecutorClient previous = clients.put(clientType, client);
        if (previous != null && previous != client) {
            LOG.warn("Replacing local executor client for type: {}", clientType);
        }
        return client;
    }

    public void unregister(String executorType) {
        String clientType = requireExecutorType(executorType);
        clients.remove(clientType);
    }

    public boolean hasClient(String executorType) {
        return executorType != null && clients.containsKey(executorType);
    }

    @Override
    public CompletionStage<NodeResult> execute(
            NodeContext nodeContext,
            Map<String, Object> variables) {

        if (nodeContext == null) {
            return failed(ErrorCode.TASK_VALIDATION_FAILED, "NodeContext cannot be null");
        }

        String clientType;
        try {
            clientType = requireExecutorType(nodeContext.nodeType());
        } catch (GamelanException e) {
            return Uni.createFrom().<NodeResult>failure(e).subscribeAsCompletionStage();
        }

        ExecutorClient client = clients.get(clientType);
        if (client == null) {
            return failed(
                    ErrorCode.TASK_EXECUTOR_UNAVAILABLE,
                    "No local executor client registered for node type: " + clientType);
        }

        try {
            LOG.debug("Executing node locally: node={}, clientType={}", nodeContext.nodeId(), clientType);
            CompletionStage<NodeResult> result = client.execute(nodeContext, variables != null ? variables : Map.of());
            if (result == null) {
                return failed(ErrorCode.RUNTIME_ERROR, "Local executor client returned null completion stage: " + clientType);
            }
            return result.thenApply(nodeResult -> {
                if (nodeResult == null) {
                    throw new GamelanException(ErrorCode.RUNTIME_ERROR,
                            "Local executor client returned null node result: " + clientType);
                }
                return nodeResult;
            });
        } catch (GamelanException e) {
            return Uni.createFrom().<NodeResult>failure(e).subscribeAsCompletionStage();
        } catch (RuntimeException e) {
            return Uni.createFrom().<NodeResult>failure(new GamelanException(
                    ErrorCode.RUNTIME_ERROR,
                    "Local executor client failed before completion: " + clientType,
                    e)).subscribeAsCompletionStage();
        }
    }

    @Override
    public String getExecutorType() {
        return EXECUTOR_TYPE;
    }

    private static String requireExecutorType(String executorType) {
        if (executorType == null || executorType.isBlank()) {
            throw new GamelanException(ErrorCode.TASK_VALIDATION_FAILED, "Local executor type is required");
        }
        return executorType.trim();
    }

    private static CompletionStage<NodeResult> failed(ErrorCode errorCode, String message) {
        return Uni.createFrom()
                .<NodeResult>failure(new GamelanException(errorCode, message))
                .subscribeAsCompletionStage();
    }
}
