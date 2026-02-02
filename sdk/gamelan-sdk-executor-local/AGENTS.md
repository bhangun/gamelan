# Implementing Workflow Agents (Executors)

This guide explains how to implement custom workflow executors (also known as Agents) within the Gamelan local runtime.

## Core Concepts

In Gamelan, an **Executor** is a component responsible for executing a specific type of node in a workflow. When a workflow reaches a node, the engine sends a task to the corresponding executor.

The **Local Executor SDK** simplifies this process for applications running the engine and executors in the same process.

## Step-by-Step Implementation

### 1. Identify the Task Type
Decide on a unique string identifier for your executor (e.g., `calculate-tax`, `send-email-v2`).

### 2. Extend AbstractWorkflowExecutor
Extend the base class and override the `execute` method.

```java
import tech.kayys.gamelan.sdk.executor.core.AbstractWorkflowExecutor;
import tech.kayys.gamelan.sdk.executor.core.Executor;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Executor(executorType = "my-task-type")
public class MyAgent extends AbstractWorkflowExecutor {

    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        // 1. Get input data from task context
        var input = task.context().get("some-input");

        // 2. Perform business logic
        // ... logic here ...

        // 3. Return a successful result with output data
        return Uni.createFrom().item(
            NodeExecutionResult.success(task, Map.of("processed-data", "success"))
        );
    }
}
```

### 3. Handle Failures
For predictable business failures, return a `failure` result:

```java
return Uni.createFrom().item(
    NodeExecutionResult.failure(task, "Invalid data received", Map.of("error-code", 400))
);
```

For unexpected technical errors, simply let the `Uni` fail, and the engine will handle retries based on the workflow configuration.

## Best Practices

- **Reactive First**: Use Mutiny's async APIs for I/O operations (database, external services) to avoid blocking the EventBus.
- **Statelessness**: Executors should be stateless. All required state should be passed through the `NodeExecutionTask` context.
- **Idempotency**: Nodes might be retried. Ensure your logic is idempotent or handles duplicates correctly.

## Registration

In the `gamelan-sdk-executor-local` module, registration is automatic. Any `@ApplicationScoped` bean that extends `WorkflowExecutor` (which `AbstractWorkflowExecutor` does) will be discovered by CDI and registered with the `LocalExecutorRuntime` on startup.
