# Executor SDK Examples

This directory contains example executors and their tests to help you get started with the Gamelan Executor SDK.

## Examples Included

### 1. OrderValidatorExecutor
**File:** [`OrderValidatorExecutor.java`](./OrderValidatorExecutor.java)  
**Test:** [`OrderValidatorExecutorTest.java`](./OrderValidatorExecutorTest.java)

A simple synchronous executor that validates order IDs.

**Features:**
- Synchronous validation logic
- Success and failure result handling
- Input validation

**Usage:**
```java
@Executor(
    executorType = "order-validator",
    communicationType = CommunicationType.GRPC,
    maxConcurrentTasks = 20
)
public class OrderValidatorExecutor extends AbstractWorkflowExecutor {
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        // Validation logic
    }
}
```

### 2. PaymentProcessorExecutor
**File:** [`PaymentProcessorExecutor.java`](./PaymentProcessorExecutor.java)

An asynchronous executor that simulates payment processing with delays.

**Features:**
- Asynchronous processing with Mutiny
- Delayed execution simulation
- Kafka communication type

## Running the Tests

```bash
# Run all example tests
mvn test -Dtest="*ExecutorTest"

# Run specific test
mvn test -Dtest="OrderValidatorExecutorTest"
```

## Creating Your Own Executor

1. **Extend AbstractWorkflowExecutor:**
```java
@Executor(
    executorType = "my-executor",
    communicationType = CommunicationType.GRPC,
    maxConcurrentTasks = 10
)
public class MyExecutor extends AbstractWorkflowExecutor {
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        // Your logic here
        return SimpleNodeExecutionResult.success(/*...*/);
    }
}
```

2. **Implement the execute() method:**
   - Extract data from `task.context()`
   - Perform your business logic
   - Return `SimpleNodeExecutionResult.success()` or `SimpleNodeExecutionResult.failure()`

3. **Write tests:**
   - Create test tasks using the helper method pattern
   - Use `UniAssertSubscriber` for testing Mutiny Uni results
   - Test both success and failure scenarios

## Key Concepts

### Task Context
Access input data via `task.context()`:
```java
Map<String, Object> context = task.context();
String value = (String) context.get("key");
```

### Success Results
```java
return SimpleNodeExecutionResult.success(
    task.runId(),
    task.nodeId(),
    task.attempt(),
    Map.of("outputKey", "outputValue"),
    task.token(),
    Duration.ofMillis(100)
);
```

### Failure Results
```java
return SimpleNodeExecutionResult.failure(
    task.runId(),
    task.nodeId(),
    task.attempt(),
    new ErrorInfo("ERROR_CODE", "Error message", "", Map.of()),
    task.token()
);
```

### Async Processing
Use Mutiny operators for async operations:
```java
return callExternalService()
    .map(response -> SimpleNodeExecutionResult.success(/*...*/));
```

## Communication Types

- **GRPC**: Low-latency, bidirectional streaming
- **KAFKA**: High-throughput, event-driven
- **REST**: Simple HTTP-based (not shown in examples)

Choose based on your use case and infrastructure.

## Best Practices

1. **Always handle errors gracefully** - Return failure results instead of throwing exceptions
2. **Use appropriate timeouts** - Set realistic timeout values for your operations
3. **Log important events** - Use SLF4J for logging
4. **Test thoroughly** - Write tests for success, failure, and edge cases
5. **Keep executors focused** - One executor should do one thing well
6. **Use lifecycle hooks** - Override `beforeExecute()` and `afterExecute()` when needed

## Need Help?

- Check the main [README](../../../../../../../README.md)
- Review the [architecture documentation](../../../../../../../docs/architecture.md)
- Look at the test cases for more examples
