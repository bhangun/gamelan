package tech.kayys.gamelan.sdk.executor.examples;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.sdk.executor.core.AbstractWorkflowExecutor;
import tech.kayys.gamelan.sdk.executor.core.Executor;
import tech.kayys.gamelan.sdk.executor.core.SimpleNodeExecutionResult;

/**
 * Example: Payment processing executor
 * Demonstrates async processing with delays
 */
@Executor(executorType = "payment-processor", communicationType = CommunicationType.KAFKA, maxConcurrentTasks = 10)
public class PaymentProcessorExecutor extends AbstractWorkflowExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentProcessorExecutor.class);

    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        Map<String, Object> context = task.context();
        Double amount = (Double) context.get("totalAmount");
        String customerId = (String) context.get("customerId");

        LOG.info("Processing payment for customer: {}, amount: {}", customerId, amount);

        // Simulate payment processing
        return processPayment(customerId, amount)
                .map(transactionId -> SimpleNodeExecutionResult.success(
                        task.runId(),
                        task.nodeId(),
                        task.attempt(),
                        Map.of(
                                "transactionId", transactionId,
                                "status", "COMPLETED",
                                "amount", amount,
                                "customerId", customerId),
                        task.token(),
                        Duration.ofSeconds(2)));
    }

    /**
     * Simulates payment gateway call with delay
     */
    private Uni<String> processPayment(String customerId, Double amount) {
        // Simulate payment gateway call
        return Uni.createFrom().item(() -> {
            LOG.debug("Calling payment gateway for customer: {}", customerId);
            return "TXN-" + UUID.randomUUID().toString();
        })
                .onItem().delayIt().by(Duration.ofSeconds(2));
    }
}
