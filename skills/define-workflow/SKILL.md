---
name: define-workflow
description: Create workflow definitions (DAG/Saga) with nodes, executors, and compensation logic for complex distributed processes
metadata:
  short-description: Define workflow structure
  category: workflow-definition
  difficulty: beginner
---

# Define Workflow Skill

Create and define workflow orchestration logic including DAG workflows, Saga patterns with compensation, retries, and error handling.

## When to Use

- You need to orchestrate multiple service calls
- You want ACID guarantees with Saga pattern
- You need complex workflows with conditional logic
- You want to handle distributed transaction compensation

## Workflow Types

### 1. DAG (Directed Acyclic Graph)
Standard workflow with sequential or parallel steps.

### 2. Saga Pattern
Distributed transaction with compensation logic for failure handling.

## Steps

### 1. Define Workflow Structure

```java
WorkflowDefinition definition = WorkflowDefinition.builder()
  .id("order-processing")
  .name("Order Processing Workflow")
  .version("1.0.0")
  .description("Process customer orders end-to-end")
  .build();
```

### 2. Add Workflow Nodes

```java
// Sequential node execution
definition.addNode(
  WorkflowNode.builder()
    .id("validate-order")
    .name("Validate Order")
    .executorId("order-validator")
    .retryPolicy(RetryPolicy.builder()
      .maxRetries(3)
      .backoffMs(1000)
      .build())
    .build()
);

definition.addNode(
  WorkflowNode.builder()
    .id("charge-payment")
    .name("Charge Payment")
    .executorId("payment-service")
    .timeout(Duration.ofSeconds(30))
    .build()
);

definition.addNode(
  WorkflowNode.builder()
    .id("ship-order")
    .name("Ship Order")
    .executorId("shipping-service")
    .build()
);
```

### 3. Define Node Dependencies

```java
// Sequential flow: validate → charge → ship
definition.addEdge("validate-order", "charge-payment");
definition.addEdge("charge-payment", "ship-order");

// Or add parallel nodes
definition.addEdge("charge-payment", "notify-warehouse");
definition.addEdge("charge-payment", "update-inventory");
```

### 4. Add Compensation (Saga Pattern)

```java
// Define what to do if workflow fails
definition.setCompensationPolicy(
  CompensationPolicy.builder()
    .strategy(CompensationStrategy.SEQUENTIAL)  // Reverse order
    .failOnCompensationError(false)
    .timeout(Duration.ofMinutes(5))
    .maxRetries(2)
    .build()
);

// Add compensation handlers
definition.addCompensation(
  CompensationHandler.builder()
    .nodeId("charge-payment")
    .executorId("payment-service")
    .action("refund")  // Execute refund if payment succeeded
    .build()
);

definition.addCompensation(
  CompensationHandler.builder()
    .nodeId("ship-order")
    .executorId("shipping-service")
    .action("cancel_shipment")
    .build()
);
```

### 5. Define Workflow Inputs/Outputs

```java
definition.addInput(
  WorkflowInput.builder()
    .name("orderId")
    .type("string")
    .required(true)
    .build()
);

definition.addInput(
  WorkflowInput.builder()
    .name("customerId")
    .type("string")
    .required(true)
    .build()
);

definition.addOutput(
  WorkflowOutput.builder()
    .name("confirmationNumber")
    .type("string")
    .sourceNode("ship-order")
    .sourcePath("confirmation_id")
    .build()
);
```

### 6. Set Error Handling

```java
definition.addErrorHandler(
  ErrorHandler.builder()
    .triggerNode("*")  // Any node
    .errorType("ServiceUnavailable")
    .action(ErrorAction.RETRY)
    .retryPolicy(RetryPolicy.builder()
      .maxRetries(5)
      .backoffMs(2000)
      .build())
    .build()
);

definition.addErrorHandler(
  ErrorHandler.builder()
    .triggerNode("charge-payment")
    .errorType("PaymentFailed")
    .action(ErrorAction.COMPENSATE)  // Trigger compensation
    .build()
);
```

### 7. Register Workflow

```java
@Inject
WorkflowDefinitionRegistry registry;

// Register the workflow
registry.register(definition);

// Or load from YAML
WorkflowDefinition loaded = registry.load(
  "definitions/order-processing.yaml"
);
```

## YAML Workflow Definition

```yaml
id: order-processing
name: Order Processing Workflow
version: 1.0.0
description: Process customer orders end-to-end

inputs:
  - name: orderId
    type: string
    required: true
  - name: customerId
    type: string
    required: true

nodes:
  - id: validate-order
    name: Validate Order
    executorId: order-validator
    retryPolicy:
      maxRetries: 3
      backoffMs: 1000
    
  - id: charge-payment
    name: Charge Payment
    executorId: payment-service
    timeout: 30s
    
  - id: ship-order
    name: Ship Order
    executorId: shipping-service

edges:
  - from: validate-order
    to: charge-payment
  - from: charge-payment
    to: ship-order
  - from: charge-payment
    to: notify-warehouse
  - from: charge-payment
    to: update-inventory

compensation:
  strategy: SEQUENTIAL
  failOnCompensationError: false
  timeout: 5m
  maxRetries: 2
  handlers:
    - nodeId: charge-payment
      executorId: payment-service
      action: refund
    - nodeId: ship-order
      executorId: shipping-service
      action: cancel_shipment

outputs:
  - name: confirmationNumber
    sourceNode: ship-order
    sourcePath: confirmation_id
```

## Workflow Patterns

### Sequential Pattern
```
Node1 → Node2 → Node3
```

### Parallel Pattern
```
        ├→ Node2
Node1 ──┤
        └→ Node3 (converges at Node4)
          ↓
       Node4
```

### Conditional Pattern
```
Node1 → [condition] → {yes: Node2, no: Node3}
        Both merge at Node4
```

## Advanced Features

### Dynamic Node Parameters

```java
// Pass parameters between nodes
definition.setNodeParameter(
  "charge-payment",
  "amount",
  "${validate-order.total}"  // Reference output from previous node
);
```

### Timeout Handling

```java
definition.addNode(
  WorkflowNode.builder()
    .id("long-running")
    .executorId("batch-processor")
    .timeout(Duration.ofMinutes(30))
    .timeoutAction(TimeoutAction.FAIL)  // or COMPENSATE
    .build()
);
```

### Custom Compensation Logic

```java
definition.addCompensation(
  CompensationHandler.builder()
    .nodeId("custom-node")
    .executorId("custom-service")
    .action("custom_cleanup")
    .retryPolicy(RetryPolicy.builder()
      .maxRetries(3)
      .backoffMs(500)
      .build())
    .build()
);
```

## Best Practices

1. **Name nodes clearly** - Easy to identify in logs
2. **Add retries** - Handle transient failures
3. **Define timeouts** - Prevent hanging workflows
4. **Plan compensation** - Always think about rollback
5. **Test edge cases** - Failure scenarios
6. **Version workflows** - Track changes over time
7. **Document inputs/outputs** - Clear contracts

## Example: Complete Order Processing Workflow

See `scripts/order-processing-example.java` for full implementation.

## Troubleshooting

### Circular Dependency
```
Error: Circular edge detected
Fix: Ensure DAG structure (no loops)
```

### Missing Executor
```
Error: Executor 'payment-service' not found
Fix: Register executor before workflow execution
```

### Invalid Input Reference
```
Error: Output path not found
Fix: Check node ID and output path syntax
```

## See Also

- [Register Executor](./register-executor.md)
- [Execute Workflow](./execute-workflow.md)
- [Handle Saga Compensation](./handle-saga-compensation.md)
- [Workflow Definition Reference](../references/workflow-definition.md)
