# Gamelan SDK Executor - Local

This module provides a local, same-JVM implementation of the Gamelan Executor runtime. It allows you to run workflow executors (also known as agents or workers) directly within the same application process as the Gamelan engine, using Vert.x EventBus for high-performance communication.

## Key Features

- **Zero-Latency Communication**: Direct internal communication via Vert.x EventBus instead of network calls (gRPC/Kafka), eliminating network overhead and improving performance.
- **Auto-Discovery**: Automatic discovery and registration of executors using Quarkus CDI, with no manual registration required.
- **Unified API**: Uses the same `AbstractWorkflowExecutor` base class as remote executors, ensuring code portability between local and distributed deployments.
- **Reactive by Design**: Built on Mutiny for non-blocking, reactive execution of workflow tasks.
- **Single-Process Simplicity**: Perfect for monolithic applications or development environments where running everything in one JVM is desirable.

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-sdk-executor-local</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Quick Start

### 1. Create Your First Executor

Implement a task executor by extending `AbstractWorkflowExecutor`:

```java
import tech.kayys.gamelan.sdk.executor.core.AbstractWorkflowExecutor;
import tech.kayys.gamelan.sdk.executor.core.Executor;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
@Executor(executorType = "calculate-tax")
public class CalculateTaxExecutor extends AbstractWorkflowExecutor {
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        // Extract input from task context
        Double amount = (Double) task.context().get("amount");
        
        // Perform calculation
        Double tax = amount * 0.1;
        
        // Return success with output data
        return Uni.createFrom().item(
            NodeExecutionResult.success(task, Map.of("tax", tax))
        );
    }
}
```

### 2. Handle Failures

For expected business failures:

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    Double amount = (Double) task.context().get("amount");
    
    if (amount <= 0) {
        return Uni.createFrom().item(
            NodeExecutionResult.failure(task, "Amount must be positive", 
                Map.of("errorCode", "INVALID_AMOUNT"))
        );
    }
    
    // ... rest of logic
}
```

For unexpected errors, let the `Uni` fail—the engine will retry based on workflow configuration:

```java
@Override
public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
    return callExternalService()
        .map(result -> NodeExecutionResult.success(task, Map.of("result", result)))
        .onFailure().recoverWithNull(); // Engine will retry based on policy
}
```

## Architecture

### LocalExecutorRuntime

The `LocalExecutorRuntime` is the core component that:
- Starts automatically on application startup (via `@Startup` annotation)
- Discovers all `@ApplicationScoped` beans extending `AbstractWorkflowExecutor`
- Registers executors in a local registry accessible to the Gamelan engine
- Manages the Vert.x EventBus for inter-process communication

### LocalExecutorTransport

Provides the communication layer between the Gamelan engine and local executors:
- Bridges task execution requests from the engine to executor implementations
- Sends execution results back to the engine
- Maintains asynchronous, non-blocking communication using Mutiny

### Execution Flow

```
Workflow Engine
    ↓
 LocalExecutorRuntime (discovers & registers executors)
    ↓
 Vert.x EventBus (internal communication)
    ↓
 Your Executor Implementations
    ↓ 
 Results back to Engine
```

## Advanced Usage

### Custom Executor Configuration

The `@Executor` annotation supports additional configuration:

```java
@Executor(
    executorType = "payment-processor",
    communicationType = CommunicationType.KAFKA,
    maxConcurrentTasks = 50
)
public class PaymentExecutor extends AbstractWorkflowExecutor {
    // ...
}
```

### Accessing Task Metadata

The `NodeExecutionTask` provides access to:
- `task.nodeId()` - ID of the workflow node being executed
- `task.runId()` - Unique workflow execution ID
- `task.attempt()` - Current retry attempt number
- `task.context()` - Input data as a Map
- `task.token()` - Unique task token for tracking

### Async I/O Operations

Use Mutiny's reactive APIs for I/O-bound operations:

```java
@ApplicationScoped
@Executor(executorType = "fetch-user-data")
public class FetchUserDataExecutor extends AbstractWorkflowExecutor {
    
    @Inject
    UserService userService;
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        String userId = (String) task.context().get("userId");
        
        return userService.fetchUserAsync(userId)
            .map(user -> NodeExecutionResult.success(task, 
                Map.of("user", user)))
            .onFailure().recoverWithItem(() -> 
                NodeExecutionResult.failure(task, "User not found"));
    }
}
```

## Best Practices

1. **Reactive First**: Always use Mutiny's async APIs for I/O operations to avoid blocking the EventBus thread.

2. **Statelessness**: Executors should be stateless. All required state should come from the `NodeExecutionTask` context or be injected via CDI.

3. **Idempotency**: Workflow tasks may be retried. Ensure your logic handles duplicate executions correctly or is idempotent.

4. **Error Handling**: Distinguish between business failures (return `NodeExecutionResult.failure()`) and technical errors (let Uni fail for retries).

5. **Logging**: Use SLF4J for structured logging to aid debugging and monitoring.

6. **Task Timeout**: Keep task execution time reasonable. Long-running operations should use asynchronous patterns.

## Examples

For complete working examples, see [docs/example.md](docs/example.md), which includes:
- Order validation executor
- Payment processing executor  
- Human approval executor with notification

## Comparison: Local vs Remote Executors

| Feature | Local | Remote |
|---------|-------|--------|
| **Communication** | Vert.x EventBus | gRPC/Kafka |
| **Latency** | Sub-millisecond | Network dependent |
| **Deployment** | Same JVM as engine | Separate process/container |
| **Scalability** | Single JVM | Horizontally scalable |
| **Development** | Simpler, easier debugging | Production-ready distribution |
| **Use Case** | Monoliths, development | Microservices, enterprise |

## Testing

The module includes test examples in `src/test/java/tech/kayys/gamelan/sdk/executor/examples/`:
- `OrderValidatorExecutorTest` - Testing basic executor logic
- `LocalExecutorRuntimeTest` - Integration testing with the runtime

## Documentation

For comprehensive guides on implementing and using agents, see:
- **[AGENTS.md](AGENTS.md)**: Detailed guide for workflow executor implementation
- **[docs/example.md](docs/example.md)**: Working code examples for common patterns

## Troubleshooting

### Executor Not Found

If your executor is not being discovered:
- Ensure the class is annotated with `@ApplicationScoped` and `@Executor`
- Verify it extends `AbstractWorkflowExecutor`
- Check that the module is on the classpath
- Review application logs for CDI initialization messages

### Task Execution Timeout

If tasks are timing out:
- Check that your executor uses reactive APIs (Mutiny) for I/O
- Avoid blocking operations on the EventBus
- Verify task execution time is reasonable
- Review the `NodeExecutionTask` context for required input data

### Results Not Returned

Ensure your `execute()` method:
- Always returns a `Uni<NodeExecutionResult>`
- Calls either `success()` or `failure()` on the result
- Properly maps errors and exceptions
