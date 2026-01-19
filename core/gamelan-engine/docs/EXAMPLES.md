# Gamelan Workflow Examples Guide

This guide provides concrete, real-world examples of how to define, execute, and monitor workflows using Gamelan.

## 1. E-commerce Order Flow (Multi-Step)

This example demonstrates a standard sequential workflow with multiple tasks and dependency management.

### Workflow Definition

```java
WorkflowDefinition orderFlow = WorkflowDefinition.builder()
    .id(WorkflowDefinitionId.of("ecommerce-order"))
    .name("Standard Order Processing")
    .version("1.0.0")
    // Step 1: Inventory
    .addNode(NodeDefinition.builder()
        .id(NodeId.of("check-inventory"))
        .type(NodeType.TASK)
        .executorType("inventory-service")
        .build())
    // Step 2: Payment (Depends on Inventory)
    .addNode(NodeDefinition.builder()
        .id(NodeId.of("charge-customer"))
        .type(NodeType.TASK)
        .executorType("payment-gateway")
        .dependsOn(List.of(NodeId.of("check-inventory")))
        .critical(true)
        .build())
    // Step 3: Logistics
    .addNode(NodeDefinition.builder()
        .id(NodeId.of("ship-order"))
        .type(NodeType.TASK)
        .executorType("shipping-provider")
        .dependsOn(List.of(NodeId.of("charge-customer")))
        .build())
    .build();
```

## 2. Implementing a REST Executor

Executors can be implemented as simple REST services. The engine will POST the `NodeExecutionTask` and expect a callback with the `NodeExecutionResult`.

### Example Controller (Quarkus)

```java
@Path("/executor/inventory")
public class InventoryExecutorResource {

    @POST
    public Uni<Response> execute(NodeExecutionTask task) {
        String sku = (String) task.context().get("sku");
        
        // Simulating logic
        return checkStock(sku)
            .map(available -> {
                // Reporting result back to the engine
                return reportResult(task, available);
            })
            .replaceWith(Response.accepted().build());
    }
}
```

## 3. Human-in-the-Loop (Wait & Signal)

For workflows that require manual intervention (e.g., manager approval), use `WAIT` nodes and external signals.

### Workflow Definition

```java
.addNode(NodeDefinition.builder()
    .id(NodeId.of("manager-approval"))
    .type(NodeType.WAIT)
    .build())
```

### Resuming via Signal

When the manager approves via your UI, send a signal to the engine:

```java
Signal approval = new Signal(
    "APPROVE", 
    NodeId.of("manager-approval"), 
    Map.of("approvedBy", "admin", "reason", "Project in budget")
);

runManager.signal(runId, approval)
    .subscribe().with(v -> System.out.println("Workflow resumed"));
```

## 4. Retries and Dead-lettering

Configure resilient execution with exponential backoff.

```java
.addNode(NodeDefinition.builder()
    .id(NodeId.of("flaky-api-call"))
    .type(NodeType.TASK)
    .executorType("external-api")
    .retryPolicy(new ExponentialRetryPolicy(
        5,                          // Max attempts
        Duration.ofSeconds(2),      // Initial delay
        Duration.ofMinutes(10),     // Max delay
        2.0                         // Multiplier
    ))
    .build())
```

If the task fails 5 times, it will be moved to a **Dead Letter** state, and a `NodeFailedEvent` will be published for monitoring.

## 5. Saga Pattern (Compensation)

Ensure data consistency across distributed systems by defining "Undo" actions.

```java
.addNode(NodeDefinition.builder()
    .id(NodeId.of("book-flight"))
    .type(NodeType.TASK)
    .executorType("flight-service")
    .compensationTask("cancel-flight") // Registered executor type for undo
    .build())
```

If a subsequent critical node fails, the engine will automatically dispatch `cancel-flight`.
