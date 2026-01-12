

```java
/**
 * Example: Order validation executor
 */
@Executor(
    executorType = "order-validator",
    communicationType = tech.kayys.silat.core.scheduler.CommunicationType.GRPC,
    maxConcurrentTasks = 20
)
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
                        "validatedAt", Instant.now().toString()
                    ),
                    task.token(),
                    Duration.ofMillis(100)
                );
            } else {
                return NodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    new ErrorInfo(
                        "INVALID_ORDER",
                        "Order ID is invalid",
                        "",
                        Map.of("orderId", orderId)
                    ),
                    task.token()
                );
            }
        });
    }
}

/**
 * Example: Payment processing executor
 */
@Executor(
    executorType = "payment-processor",
    communicationType = tech.kayys.silat.core.scheduler.CommunicationType.KAFKA
)
public class PaymentProcessorExecutor extends AbstractWorkflowExecutor {
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        Map<String, Object> context = task.context();
        Double amount = (Double) context.get("totalAmount");
        String customerId = (String) context.get("customerId");
        
        // Simulate payment processing
        return processPayment(customerId, amount)
            .map(transactionId -> NodeExecutionResult.success(
                task.runId(),
                task.nodeId(),
                task.attempt(),
                Map.of(
                    "transactionId", transactionId,
                    "status", "COMPLETED",
                    "processedAt", Instant.now().toString()
                ),
                task.token()
            ));
    }
    
    private Uni<String> processPayment(String customerId, Double amount) {
        // Simulate payment gateway call
        return Uni.createFrom().item(UUID.randomUUID().toString())
            .onItem().delayIt().by(Duration.ofSeconds(2));
    }
}

/**
 * Example: Human task executor (sends notification and waits)
 */
@Executor(
    executorType = "human-approver",
    communicationType = tech.kayys.silat.core.scheduler.CommunicationType.GRPC
)
public class HumanApprovalExecutor extends AbstractWorkflowExecutor {
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        Map<String, Object> context = task.context();
        
        // Send notification to approver
        String approverEmail = (String) context.get("approverEmail");
        sendApprovalRequest(approverEmail, task);
        
        // Return pending result - workflow will suspend
        // Actual approval comes via signal
        return Uni.createFrom().item(
            NodeExecutionResult.success(
                task.runId(),
                task.nodeId(),
                task.attempt(),
                Map.of(
                    "status", "PENDING_APPROVAL",
                    "notificationSent", true
                ),
                task.token()
            )
        );
    }
    
    private void sendApprovalRequest(String email, NodeExecutionTask task) {
        // Send email/notification
    }
}
```

