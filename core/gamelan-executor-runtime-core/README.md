# Gamelan Executor Runtime Core

This module provides the **core abstractions and base classes** for executor runtimes. It defines the foundational contracts that allow different executor implementations (local, remote, Python, etc.) to integrate with the Gamelan workflow engine.

## Overview

The Executor Runtime Core module serves as the bridge between executors and the Gamelan engine. It provides:
- Abstract base executor class
- Executor lifecycle management
- Task reception and result reporting
- Transport-agnostic executor interface
- Executor registration contracts
- Base classes for different transport mechanisms

## Key Features

- **Unified Executor API**: Common interface for all executor types (local, remote, Python)
- **Lifecycle Management**: Startup, registration, execution, and shutdown hooks
- **Transport Abstraction**: Support for gRPC, Kafka, and REST transports
- **Task Execution**: Receive tasks and report results
- **Error Handling**: Consistent error handling across executor types
- **Reactive Design**: Built on Mutiny for non-blocking operations

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-executor-runtime-core</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Core Components

### 1. AbstractWorkflowExecutor

Base class for implementing executors:

```java
public abstract class AbstractWorkflowExecutor implements WorkflowExecutor {
    
    /**
     * Execute a workflow task
     * @param task The task to execute
     * @return Uni with execution result
     */
    public abstract Uni<NodeExecutionResult> execute(NodeExecutionTask task);
    
    protected final Logger LOG = LoggerFactory.getLogger(getClass());
}
```

### 2. WorkflowExecutor Interface

Defines executor contract:

```java
public interface WorkflowExecutor {
    
    /**
     * Execute a task
     */
    Uni<NodeExecutionResult> execute(NodeExecutionTask task);
    
    /**
     * Get executor metadata
     */
    ExecutorMetadata getMetadata();
    
    /**
     * Check if executor can handle task type
     */
    boolean canExecute(String taskType);
}
```

### 3. BaseExecutorRuntime

Abstract runtime managing executor lifecycle:

```java
public abstract class BaseExecutorRuntime {
    
    /**
     * Initialize the executor runtime
     */
    protected abstract void initialize();
    
    /**
     * Create transport for communication
     */
    protected abstract ExecutorTransport createTransport();
    
    /**
     * Start the runtime
     */
    public void start() {
        initialize();
        // Discover and register executors
        // Start transport
    }
    
    /**
     * Stop the runtime
     */
    public void stop() {
        // Unregister executors
        // Stop transport
    }
}
```

### 4. ExecutorTransport Interface

Define communication with the engine:

```java
public interface ExecutorTransport {
    
    /**
     * Register executors with engine
     */
    Uni<Void> register(List<WorkflowExecutor> executors);
    
    /**
     * Unregister from engine
     */
    Uni<Void> unregister();
    
    /**
     * Receive task from engine
     */
    Uni<NodeExecutionTask> receiveTask();
    
    /**
     * Report task result
     */
    Uni<Void> reportResult(NodeExecutionResult result);
    
    /**
     * Send heartbeat
     */
    Uni<Void> sendHeartbeat();
}
```

### 5. ExecutorMetadata

Executor capabilities and information:

```java
public class ExecutorMetadata {
    private String executorId;
    private String executorType;
    private String version;
    private int maxConcurrentTasks;
    private List<String> supportedTaskTypes;
    private Map<String, Object> capabilities;
}
```

## Executor Lifecycle

```
1. Initialization
   └─ Executor instance created
   └─ @PostConstruct or init() called

2. Discovery
   └─ Runtime discovers executor beans
   └─ Collects executor metadata

3. Registration
   └─ Executors register with engine
   └─ Engine validates capabilities
   └─ Executor added to registry

4. Execution
   └─ Engine sends tasks to executor
   └─ Executor processes tasks
   └─ Results reported back

5. Shutdown
   └─ Unregister from engine
   └─ Clean up resources
   └─ @PreDestroy or shutdown() called
```

## Implementing a Custom Executor

### Step 1: Extend AbstractWorkflowExecutor

```java
public class MyCustomExecutor extends AbstractWorkflowExecutor {
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        // Extract input
        String input = (String) task.context().get("input");
        
        // Process task
        String result = processInput(input);
        
        // Return success
        return Uni.createFrom().item(
            NodeExecutionResult.success(task, Map.of("result", result))
        );
    }
    
    private String processInput(String input) {
        // Your business logic
        return input.toUpperCase();
    }
}
```

### Step 2: Provide Metadata

```java
public class MyCustomExecutor extends AbstractWorkflowExecutor {
    
    @Override
    public ExecutorMetadata getMetadata() {
        return ExecutorMetadata.builder()
            .executorId("my-executor-1")
            .executorType("text-processor")
            .version("1.0.0")
            .maxConcurrentTasks(50)
            .supportedTaskTypes(List.of("text-processor"))
            .capabilities(Map.of(
                "language", "Java",
                "async", true
            ))
            .build();
    }
    
    @Override
    public boolean canExecute(String taskType) {
        return "text-processor".equals(taskType);
    }
}
```

### Step 3: Handle Task Execution

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    return Uni.createFrom().item(() -> {
        try {
            // Business logic
            Map<String, Object> output = doWork(task.context());
            
            // Success result
            return NodeExecutionResult.success(task, output);
        } catch (BusinessException e) {
            // Expected failure
            return NodeExecutionResult.failure(task, 
                e.getMessage(),
                Map.of("errorCode", e.getCode()));
        } catch (Exception e) {
            // Unexpected error - let it fail for retry
            throw e;
        }
    });
}
```

## Task Execution Patterns

### 1. Synchronous Execution

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    String input = (String) task.context().get("data");
    String result = processSync(input);  // Blocking operation
    return Uni.createFrom().item(
        NodeExecutionResult.success(task, Map.of("result", result))
    );
}
```

### 2. Asynchronous I/O

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    return externalService.fetchDataAsync(task.context())
        .map(data -> NodeExecutionResult.success(task, 
            Map.of("data", data)))
        .onFailure().recoverWithItem(failure -> 
            NodeExecutionResult.failure(task, 
                "Service unavailable"));
}
```

### 3. Retry with Backoff

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    return callExternalApi(task)
        .onFailure().retry()
            .withBackOff(Duration.ofSeconds(1))
            .atMost(3)
        .map(result -> NodeExecutionResult.success(task, result))
        .onFailure().recoverWithItem(failure -> 
            NodeExecutionResult.failure(task, 
                "Max retries exceeded"));
}
```

### 4. Timeout Handling

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    return longRunningOperation(task)
        .timeout().in(Duration.ofMinutes(5))
        .map(result -> NodeExecutionResult.success(task, result))
        .onFailure(TimeoutException.class)
            .recoverWithItem(() -> NodeExecutionResult.failure(task, 
                "Operation timed out"));
}
```

## Error Handling

### Business Failures

Return failure result for expected errors:

```java
if (input == null) {
    return Uni.createFrom().item(
        NodeExecutionResult.failure(task, 
            "Input is required",
            Map.of("errorCode", "INVALID_INPUT"))
    );
}
```

### Technical Errors

Let the Uni fail for transient errors (engine will retry):

```java
return externalService.call(input)
    .map(response -> processResponse(response))
    // Don't catch - let engine retry
```

### Permanent Failures

Return failure after retries exhausted:

```java
return retryableOperation()
    .onFailure().retry().atMost(3)
    .onFailure().recoverWithItem(failure -> 
        NodeExecutionResult.failure(task, 
            "Operation failed permanently"));
```

## Configuration

### Executor Configuration

Configure executor behavior in `application.properties`:

```properties
# Executor settings
gamelan.executor.max-concurrent-tasks=50
gamelan.executor.task-timeout=300
gamelan.executor.enable-async=true

# Retry configuration
gamelan.executor.max-retries=3
gamelan.executor.retry-backoff=1000

# Monitoring
gamelan.executor.enable-metrics=true
gamelan.executor.enable-tracing=true
```

## Best Practices

1. **Always Return a Result**: Never return null from execute()
   ```java
   // Always wrap in NodeExecutionResult
   return Uni.createFrom().item(
       NodeExecutionResult.success(task, output)
   );
   ```

2. **Use Mutiny for Async**: Avoid blocking operations
   ```java
   // Good: non-blocking
   return asyncService.call()
       .map(result -> NodeExecutionResult.success(...));
   
   // Avoid: blocking
   Object result = syncService.call();  // Blocks thread!
   ```

3. **Distinguish Error Types**: Business vs technical failures
   ```java
   // Business failure: return failure result
   if (validation fails) {
       return Uni.createFrom().item(
           NodeExecutionResult.failure(task, "Validation failed"));
   }
   
   // Technical error: let Uni fail
   return externalService.call()  // May retry
       .onFailure().recoverWithItem(...);
   ```

4. **Include Error Details**: Return meaningful error information
   ```java
   ErrorInfo error = new ErrorInfo(
       "INSUFFICIENT_FUNDS",
       "Account balance too low",
       "Payment amount: $500, Available: $100",
       Map.of("amount", 500, "balance", 100)
   );
   ```

5. **Set Task Metadata**: Use executor annotations
   ```java
   @Executor(
       executorType = "payment-processor",
       maxConcurrentTasks = 25,
       timeout = 60  // seconds
   )
   public class PaymentExecutor extends AbstractWorkflowExecutor { ... }
   ```

## Metrics and Monitoring

Collect metrics on executor performance:

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    long startTime = System.currentTimeMillis();
    
    return performWork(task)
        .map(result -> {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordExecution(task.nodeId(), duration, "SUCCESS");
            return NodeExecutionResult.success(task, result);
        })
        .onFailure().recoverWithItem(failure -> {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordExecution(task.nodeId(), duration, "FAILURE");
            return NodeExecutionResult.failure(task, failure.getMessage());
        });
}
```

## Testing

Test executors with a mock transport:

```java
@Test
public void testExecutor() {
    MyExecutor executor = new MyExecutor();
    
    NodeExecutionTask task = NodeExecutionTask.builder()
        .runId(WorkflowRunId.of("test-run"))
        .nodeId(NodeId.of("test-node"))
        .context(Map.of("input", "test"))
        .build();
    
    executor.execute(task)
        .subscribe().with(result -> {
            assertTrue(result.isSuccess());
            assertEquals("TEST", result.output().get("result"));
        });
}
```

## Troubleshooting

### Executor Not Registered

**Problem**: Executor not appearing as available to engine
- Verify extends AbstractWorkflowExecutor
- Check @Executor annotation present
- Ensure proper scope (ApplicationScoped for local)
- Verify runtime startup completes

**Solution**:
```java
@Executor(executorType = "my-executor")  // Required annotation
@ApplicationScoped  // Required CDI scope
public class MyExecutor extends AbstractWorkflowExecutor {
    // Implementation
}
```

### Tasks Timing Out

**Problem**: Tasks taking longer than expected
- Check for blocking I/O operations
- Verify Mutiny async patterns used correctly
- Increase timeout configuration
- Monitor actual execution duration

**Solution**:
```properties
# Increase timeout
gamelan.executor.task-timeout=600

# Or use timeout in executor
return operation()
    .timeout().in(Duration.ofMinutes(5))
```

### High Error Rate

**Problem**: Many task failures
- Review error logs for patterns
- Check input validation
- Verify external service connectivity
- Implement proper error handling

**Solution**:
```java
// Add validation and error details
if (!isValid(input)) {
    return Uni.createFrom().item(
        NodeExecutionResult.failure(task, 
            "Invalid input: " + error, 
            Map.of("input", input, "details", details))
    );
}
```

## See Also

- **[gamelan-sdk-executor-core](../../sdk/gamelan-sdk-executor-core/README.md)**: SDK wrapper for this module
- **[gamelan-sdk-executor-local](../../sdk/gamelan-sdk-executor-local/README.md)**: Local executor implementation
- **[gamelan-sdk-executor-remote](../../sdk/gamelan-sdk-executor-remote/README.md)**: Remote executor implementation
- **[gamelan-executor-registry](../gamelan-executor-registry/README.md)**: Executor registry management
