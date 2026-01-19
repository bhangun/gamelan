package tech.kayys.gamelan.sdk.executor.examples;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.model.CommunicationType;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.execution.NodeExecutionResult;
import tech.kayys.gamelan.execution.NodeExecutionTask;
import tech.kayys.gamelan.model.ErrorInfo;
import tech.kayys.gamelan.sdk.executor.AbstractWorkflowExecutor;
import tech.kayys.gamelan.sdk.executor.Executor;
import tech.kayys.gamelan.sdk.executor.SimpleNodeExecutionResult;

/**
 * Example: Order validation executor
 */
@Executor(executorType = "order-validator", communicationType = CommunicationType.GRPC, maxConcurrentTasks = 20)
public class OrderValidatorExecutor extends AbstractWorkflowExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(OrderValidatorExecutor.class);

    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        Map<String, Object> context = task.context();
        String orderId = (String) context.get("orderId");

        LOG.info("Validating order: {}", orderId);

        // Simulate validation
        return Uni.createFrom().item(() -> {
            boolean valid = orderId != null && orderId.startsWith("ORDER-");

            if (valid) {
                return SimpleNodeExecutionResult.success(
                        task.runId(),
                        task.nodeId(),
                        task.attempt(),
                        Map.of(
                                "valid", true,
                                "validatedAt", Instant.now().toString()),
                        task.token(),
                        Duration.ofMillis(100));
            } else {
                return SimpleNodeExecutionResult.failure(
                        task.runId(),
                        task.nodeId(),
                        task.attempt(),
                        new ErrorInfo(
                                "INVALID_ORDER",
                                "Order ID is invalid",
                                "",
                                Map.of("orderId", orderId != null ? orderId : "null")),
                        task.token());
            }
        });
    }
}
