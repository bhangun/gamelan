---
name: handle-saga-compensation
description: Implement Saga pattern compensation for distributed transaction rollback and failure recovery
metadata:
  short-description: Handle distributed transaction compensation
  category: saga-pattern
  difficulty: intermediate
---

# Handle Saga Compensation Skill

Implement Saga pattern compensation to handle failures in distributed workflows with automatic or manual rollback logic.

## When to Use

- You have distributed transactions across multiple services
- You need ACID-like guarantees without traditional transactions
- You want automatic rollback on failure
- You need fine-grained control over compensation

## Saga Compensation Strategies

### 1. Sequential Compensation
Compensation happens in reverse order of execution.

```
Execution:     Node1 → Node2 → Node3 [ERROR]
Compensation:  Node3 ← Node2 ← Node1
```

### 2. Parallel Compensation
All compensation handlers run simultaneously.

```
Execution:     Node1 → Node2 → Node3 [ERROR]
Compensation:  Node1 & Node2 & Node3 (parallel)
```

### 3. Custom Compensation
Plugin-defined compensation strategy.

## Steps

### 1. Define Compensation Policy

```java
CompensationPolicy policy = CompensationPolicy.builder()
  .strategy(CompensationStrategy.SEQUENTIAL)
  .failOnCompensationError(false)  // Don't fail if compensation fails
  .timeout(Duration.ofMinutes(5))
  .maxRetries(2)
  .build();

workflowDefinition.setCompensationPolicy(policy);
```

### 2. Add Compensation Handlers

```java
// Define what happens if 'charge-payment' needs to rollback
workflowDefinition.addCompensation(
  CompensationHandler.builder()
    .nodeId("charge-payment")
    .executorId("payment-service")
    .action("refund")
    .input(Map.of(
      "transactionId", "${charge-payment.transactionId}"
    ))
    .retryPolicy(RetryPolicy.builder()
      .maxRetries(3)
      .backoffMs(1000)
      .build())
    .timeout(Duration.ofSeconds(30))
    .build()
);

// Define shipment cancellation
workflowDefinition.addCompensation(
  CompensationHandler.builder()
    .nodeId("ship-order")
    .executorId("shipping-service")
    .action("cancel_shipment")
    .input(Map.of(
      "shipmentId", "${ship-order.shipmentId}"
    ))
    .build()
);

// Define inventory restock
workflowDefinition.addCompensation(
  CompensationHandler.builder()
    .nodeId("update-inventory")
    .executorId("inventory-service")
    .action("restock")
    .input(Map.of(
      "itemId", "${update-inventory.itemId}",
      "quantity", "${update-inventory.quantity}"
    ))
    .build()
);
```

### 3. Automatic Compensation on Failure

```java
// Workflow automatically compensates on failure
WorkflowExecutionRequest request = 
  WorkflowExecutionRequest.builder()
    .workflowId("order-processing")
    .inputs(Map.of(
      "orderId", "ORD-123",
      "customerId", "CUST-456"
    ))
    .enableCompensation(true)  // Enable compensation
    .build();

Uni<WorkflowExecutionResult> result = 
  executionService.execute(request);

result.onFailure().invoke(error -> {
  // Compensation is automatically triggered
  System.out.println("Compensation started due to: " + 
    error.getMessage());
});
```

### 4. Manual Compensation Control

```java
// Get execution and check if compensation is needed
WorkflowExecution execution = 
  executionService.getExecution(executionId);

if (execution.needsCompensation()) {
  // Manually trigger compensation
  CompensationService compensationService = 
    CDI.current().select(CompensationService.class).get();
  
  Uni<CompensationResult> compensationResult = 
    compensationService.compensate(execution);
  
  compensationResult.onItem().invoke(result -> {
    System.out.println("Compensation result: " + 
      result.getMessage());
  });
}
```

### 5. Selective Node Compensation

```java
// Only compensate specific nodes
CompensationService service = /* ... */;

Uni<CompensationResult> result = service.compensateNode(
  execution,
  "charge-payment"  // Only compensate payment
);
```

### 6. Custom Compensation Logic

```java
// Implement custom compensation executor
public class CustomPaymentCompensator 
    implements CompensationExecutor {
  
  @Override
  public Uni<CompensationResult> compensate(
      CompensationRequest request) {
    
    String transactionId = 
      (String) request.getInput("transactionId");
    
    // Complex compensation logic
    return refundPayment(transactionId)
      .chain(() -> notifyCustomer(transactionId))
      .chain(() -> updateAuditLog(transactionId))
      .onItem().transform(v -> 
        new CompensationResult(true, "Refund processed")
      )
      .onFailure().recoverWithItem(ex -> 
        new CompensationResult(false, ex.getMessage())
      );
  }
  
  private Uni<Void> refundPayment(String transactionId) {
    // Call payment service to refund
    return paymentService.refund(transactionId);
  }
}
```

### 7. Compensation with Data Consistency

```java
// Use idempotency keys for safe compensation
CompensationHandler handler = 
  CompensationHandler.builder()
    .nodeId("charge-payment")
    .executorId("payment-service")
    .action("refund")
    .idempotencyKey("${charge-payment.transactionId}")
    .input(Map.of(
      "transactionId", "${charge-payment.transactionId}",
      "amount", "${charge-payment.amount}"
    ))
    .build();

workflowDefinition.addCompensation(handler);
```

### 8. Saga with Rollback State

```java
// Track compensation state
SagaState state = execution.getSagaState();

switch (state.getCompensationStatus()) {
  case NOT_NEEDED:
    System.out.println("No compensation needed");
    break;
  case IN_PROGRESS:
    System.out.println("Compensation in progress");
    System.out.println("Completed nodes: " + 
      state.getCompensatedNodes());
    break;
  case COMPLETED:
    System.out.println("Compensation completed");
    System.out.println("All nodes compensated");
    break;
  case FAILED:
    System.out.println("Compensation FAILED");
    System.out.println("Manually review required");
    System.out.println("Failed nodes: " + 
      state.getFailedCompensations());
    break;
}
```

### 9. Compensation Policies

#### Strict Policy
Fail workflow if compensation fails.

```java
CompensationPolicy.builder()
  .strategy(CompensationStrategy.SEQUENTIAL)
  .failOnCompensationError(true)  // Fail on error
  .build()
```

#### Best Effort Policy
Log compensation errors but don't fail.

```java
CompensationPolicy.builder()
  .strategy(CompensationStrategy.PARALLEL)
  .failOnCompensationError(false)  // Continue on error
  .build()
```

### 10. Compensation Monitoring

```java
// Track compensation execution
executionService.watchExecution(executionId)
  .filter(event -> 
    event.getType() == WorkflowEventType.COMPENSATION_*
  )
  .onItem().invoke(event -> {
    System.out.println("Compensation event: " + 
      event.getType());
    System.out.println("Node: " + event.getNodeId());
    System.out.println("Status: " + event.getStatus());
  });
```

## Compensation Patterns

### Pattern 1: Payment Refund

```java
workflowDefinition.addCompensation(
  CompensationHandler.builder()
    .nodeId("charge-payment")
    .executorId("payment-service")
    .action("refund")
    .input(Map.of(
      "transactionId", 
        "${charge-payment.transactionId}",
      "amount", "${charge-payment.amount}"
    ))
    .timeout(Duration.ofSeconds(30))
    .retryPolicy(RetryPolicy.builder()
      .maxRetries(5)
      .backoffMs(2000)
      .build())
    .build()
);
```

### Pattern 2: Inventory Rollback

```java
workflowDefinition.addCompensation(
  CompensationHandler.builder()
    .nodeId("reserve-inventory")
    .executorId("inventory-service")
    .action("release_reservation")
    .input(Map.of(
      "reservationId", 
        "${reserve-inventory.reservationId}"
    ))
    .build()
);
```

### Pattern 3: Booking Cancellation

```java
workflowDefinition.addCompensation(
  CompensationHandler.builder()
    .nodeId("book-hotel")
    .executorId("hotel-service")
    .action("cancel_booking")
    .input(Map.of(
      "bookingId", "${book-hotel.bookingId}",
      "reason", "Order processing failed"
    ))
    .build()
);
```

## Error Scenarios

### Scenario 1: Compensation Timeout
Node compensation takes too long.

```
→ Retry compensation
→ Or manually intervene
→ Or mark as partially compensated
```

### Scenario 2: Compensation Failure
Compensation executor returns error.

```
→ Retry based on policy
→ Log for manual review
→ Alert operations team
```

### Scenario 3: Multiple Failures
Both execution and compensation fail.

```
→ Flag for manual intervention
→ Provide detailed logs
→ Mark data as potentially inconsistent
```

## Best Practices

1. **Idempotent Compensation** - Safe to retry
2. **Fast Compensation** - Keep timeouts reasonable
3. **Detailed Logging** - Track compensation steps
4. **Test Failures** - Simulate failure scenarios
5. **Manual Override** - Allow human intervention
6. **Alert on Failure** - Notify operations
7. **Data Consistency** - Verify final state

## Troubleshooting

### Compensation Not Triggered
```
Error: Compensation didn't execute
Fix: Verify compensations defined in workflow
Check: enableCompensation flag is true
```

### Compensation Timeout
```
Error: Compensation exceeded timeout
Fix: Increase timeout duration
Check: External service performance
```

### Partial Compensation
```
Error: Some nodes not compensated
Fix: Manually compensate missing nodes
Check: Compensation handler definitions
```

## See Also

- [Define Workflow](./define-workflow.md)
- [Execute Workflow](./execute-workflow.md)
- [Saga Pattern Reference](../references/saga-pattern.md)
- [Data Consistency Guide](../references/data-consistency.md)
