---
name: execute-workflow
description: Trigger workflow execution instances with input parameters, monitoring, and result handling
metadata:
  short-description: Run workflow instances
  category: workflow-execution
  difficulty: beginner
---

# Execute Workflow Skill

Trigger workflow execution instances with input parameters, track progress, handle results, and manage workflow lifecycle.

## When to Use

- You need to start a workflow execution
- You want to monitor workflow progress
- You need to handle workflow results and errors
- You want to cancel or pause running workflows

## Prerequisites

1. Workflow must be defined and registered
2. All executors referenced in workflow must be registered
3. Workflow runtime must be running

## Steps

### 1. Create Workflow Execution Request

```java
WorkflowExecutionRequest request = 
  WorkflowExecutionRequest.builder()
    .workflowId("order-processing")
    .workflowVersion("1.0.0")
    .inputs(Map.of(
      "orderId", "ORD-12345",
      "customerId", "CUST-67890",
      "amount", 99.99
    ))
    .priority(ExecutionPriority.NORMAL)
    .correlationId("order-exec-" + System.currentTimeMillis())
    .build();
```

### 2. Execute Workflow

```java
@Inject
WorkflowExecutionService executionService;

// Synchronous execution (wait for completion)
Uni<WorkflowExecutionResult> result = 
  executionService.execute(request);

result.onItem().invoke(execution -> {
  System.out.println("Execution ID: " + execution.getExecutionId());
  System.out.println("Status: " + execution.getStatus());
  System.out.println("Output: " + execution.getOutputs());
});
```

### 3. Asynchronous Execution

```java
// Fire and forget
executionService.executeAsync(request)
  .onItem().invoke(executionId -> {
    System.out.println("Started execution: " + executionId);
  });

// Later: poll for results
String executionId = "exec-xyz-123";
Uni<WorkflowExecutionResult> status = 
  executionService.getStatus(executionId);
```

### 4. Get Execution Status

```java
// Get current status
WorkflowExecution execution = 
  executionService.getExecution(executionId);

System.out.println("Status: " + execution.getStatus());
System.out.println("Progress: " + execution.getProgress());
System.out.println("Active Node: " + execution.getCurrentNodeId());
System.out.println("Started: " + execution.getStartTime());
System.out.println("Duration: " + execution.getDuration());
```

### 5. Monitor Workflow Progress

```java
// Subscribe to execution events
executionService.watchExecution(executionId)
  .onItem().invoke(event -> {
    switch (event.getType()) {
      case WORKFLOW_STARTED:
        System.out.println("Workflow started");
        break;
      case NODE_STARTED:
        System.out.println("Node started: " + 
          event.getNodeId());
        break;
      case NODE_COMPLETED:
        System.out.println("Node completed: " + 
          event.getNodeId() + 
          " (output: " + event.getOutput() + ")");
        break;
      case NODE_FAILED:
        System.out.println("Node failed: " + 
          event.getNodeId() + 
          " - " + event.getError());
        break;
      case WORKFLOW_COMPLETED:
        System.out.println("Workflow completed");
        System.out.println("Output: " + event.getOutput());
        break;
      case WORKFLOW_FAILED:
        System.out.println("Workflow failed: " + 
          event.getError());
        break;
    }
  });
```

### 6. Handle Workflow Results

```java
result.onItem().invoke(execution -> {
  if (execution.isSuccess()) {
    // Access outputs
    Map<String, Object> outputs = execution.getOutputs();
    String confirmationNumber = 
      (String) outputs.get("confirmationNumber");
    
    System.out.println("Success! Confirmation: " + 
      confirmationNumber);
  }
})
.onFailure().invoke(error -> {
  System.out.println("Workflow failed: " + 
    error.getMessage());
  
  // Check if compensation was triggered
  if (error instanceof CompensationException) {
    System.out.println("Compensation executed");
  }
});
```

### 7. Handle Workflow Failures

```java
result.onFailure().recover(error -> {
  if (error instanceof TimeoutException) {
    // Timeout - workflow took too long
    executionService.cancel(executionId);
    return Uni.createFrom().failure(error);
  } else if (error instanceof CompensationException) {
    // Compensation failed - data may be inconsistent
    return Uni.createFrom().failure(
      new DataInconsistencyException(error)
    );
  } else {
    // Other error
    return Uni.createFrom().failure(error);
  }
});
```

### 8. Cancel Workflow

```java
// Cancel running workflow
Uni<Void> cancelled = 
  executionService.cancel(executionId);

cancelled.onItem().invoke(() -> {
  System.out.println("Workflow cancelled");
  System.out.println("Compensation will be triggered");
});
```

### 9. Pause and Resume

```java
// Pause workflow execution
executionService.pause(executionId)
  .onItem().invoke(() -> {
    System.out.println("Workflow paused");
  });

// Later: resume
executionService.resume(executionId)
  .onItem().invoke(() -> {
    System.out.println("Workflow resumed");
  });
```

### 10. Retry Failed Node

```java
// Retry specific node
executionService.retryNode(executionId, "charge-payment")
  .onItem().invoke(() -> {
    System.out.println("Node retry initiated");
  });
```

## Complete Example: Order Processing

```java
@ApplicationScoped
@Path("/api/orders")
public class OrderService {
  
  @Inject
  WorkflowExecutionService executionService;
  
  @POST
  @Path("/{orderId}")
  public Uni<Response> processOrder(
      @PathParam String orderId,
      OrderRequest orderRequest) {
    
    // Create execution request
    WorkflowExecutionRequest request = 
      WorkflowExecutionRequest.builder()
        .workflowId("order-processing")
        .workflowVersion("1.0.0")
        .inputs(Map.of(
          "orderId", orderId,
          "customerId", orderRequest.getCustomerId(),
          "amount", orderRequest.getAmount()
        ))
        .correlationId("order-" + orderId)
        .build();
    
    // Execute workflow
    return executionService.execute(request)
      .onItem().transform(result -> {
        if (result.isSuccess()) {
          return Response.ok(Map.of(
            "status", "completed",
            "confirmation", 
              result.getOutputs().get("confirmationNumber")
          )).build();
        } else {
          return Response.status(400)
            .entity(Map.of(
              "status", "failed",
              "error", result.getError()
            ))
            .build();
        }
      })
      .onFailure().recoverWithItem(error -> 
        Response.status(500)
          .entity(Map.of("error", error.getMessage()))
          .build()
      );
  }
}
```

## Execution States

```
PENDING → RUNNING → (COMPLETED | FAILED | CANCELLED)
          ↓
       PAUSED → RESUMED → RUNNING
```

## Workflow Execution Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `priority` | Enum | NORMAL | Execution priority (LOW, NORMAL, HIGH) |
| `timeout` | Duration | 1 hour | Max execution time |
| `correlationId` | String | auto | Track related executions |
| `retryCount` | Integer | 0 | Global retry limit |
| `enableCompensation` | Boolean | true | Enable Saga compensation |
| `dryRun` | Boolean | false | Plan execution without running |

## Query Workflow Executions

```java
// Get all executions for a workflow
List<WorkflowExecution> executions = 
  executionService.listExecutions("order-processing");

// Get recent executions
executions = executionService.listExecutions(
  "order-processing",
  PageRequest.of(0, 10),
  Sort.by("startTime").descending()
);

// Get executions with filters
executions = executionService.listExecutions(
  ExecutionFilter.builder()
    .workflowId("order-processing")
    .status(ExecutionStatus.COMPLETED)
    .startTimeAfter(Instant.now().minus(Duration.ofDays(7)))
    .build()
);
```

## Streaming Results

```java
// Stream workflow events as they happen
Multi<WorkflowEvent> events = 
  executionService.streamExecution(executionId);

events
  .onItem().invoke(event -> {
    System.out.println(event.getTimestamp() + 
      " - " + event.getType() + ": " + 
      event.getNodeId());
  })
  .collect().asList()
  .onItem().invoke(allEvents -> {
    System.out.println("Received " + allEvents.size() + 
      " events");
  });
```

## Error Handling

### Transient Errors
Auto-retried based on executor's retry policy.

### Permanent Errors
Trigger compensation if Saga pattern enabled.

### Timeout
Configurable per workflow or globally.

## Performance Considerations

- **Synchronous Wait**: Blocks caller
- **Asynchronous Polling**: Check status periodically
- **Event Streaming**: Real-time updates via Server-Sent Events

## Best Practices

1. **Set Correlations** - Track related executions
2. **Handle Timeouts** - Set appropriate limits
3. **Monitor Progress** - Use event streaming
4. **Plan Compensation** - Know failure scenarios
5. **Log Execution** - Audit trail

## See Also

- [Define Workflow](./define-workflow.md)
- [Register Executor](./register-executor.md)
- [Handle Saga Compensation](./handle-saga-compensation.md)
- [Monitor Workflow](./monitor-workflow.md)
