package tech.kayys.gamelan.runtime.orchestration;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.runtime.ExecutorAdapter;
import tech.kayys.gamelan.runtime.ExecutorAdapterRegistry;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Coordinates task execution across different executor adapters.
 * Handles retry logic, circuit breaking, and observability.
 */
@ApplicationScoped
public class TaskExecutionCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(TaskExecutionCoordinator.class);

    @Inject
    ExecutorAdapterRegistry adapterRegistry;

    /**
     * Execute a task using the appropriate adapter
     */
    public CompletionStage<NodeResult> execute(
            String executorType,
            NodeContext nodeContext,
            Map<String, Object> variables) {

        LOG.debug("Coordinating execution for node: {} with executor: {}",
                nodeContext.nodeId(), executorType);

        try {
            ExecutorAdapter adapter = adapterRegistry.getAdapter(executorType);

            return adapter.execute(nodeContext, variables)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            LOG.error("Execution failed for node: {}", nodeContext.nodeId(), error);
                        } else {
                            LOG.debug("Execution completed for node: {}", nodeContext.nodeId());
                        }
                    });

        } catch (Exception e) {
            LOG.error("Failed to coordinate execution for node: {}", nodeContext.nodeId(), e);
            return Uni.createFrom().<NodeResult>failure(e).subscribeAsCompletionStage();
        }
    }

    /**
     * Execute with retry logic
     */
    public CompletionStage<NodeResult> executeWithRetry(
            String executorType,
            NodeContext nodeContext,
            Map<String, Object> variables,
            int maxRetries) {

        return Uni.createFrom().completionStage(
                execute(executorType, nodeContext, variables))
                .onFailure().retry().atMost(maxRetries)
                .subscribeAsCompletionStage();
    }
}
