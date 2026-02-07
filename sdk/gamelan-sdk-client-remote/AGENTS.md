# Working with Gamelan Agents (Executors)

Agents (conceptually known as Executors in the Gamelan SDK) are the workers that perform the actual tasks defined in your workflows. This guide covers how to design workflows that interact with agents and how to implement them.

## Conceptual Overview

1. **Workflow Definition**: In the SDK client, you define which "executor type" should handle a specific node.
2. **Task Dispatch**: When a run reaches that node, the Gamelan engine dispatches a `NodeExecutionTask` to any executor registered for that type.
3. **Execution**: The executor processes the task and returns a `NodeExecutionResult`.

## Defining Agent nodes

When building a workflow, use the `.execute()` method to specify an agent:

```java
client.defineWorkflow("order-processing")
    .startNode("validate")
        .execute("inventory-agent") // Matches executorType in Agent
    .then("charge")
        .execute("payment-agent")
    .end()
    .build();
```

## Implementing an Agent

To implement an agent, you should use the `gamelan-sdk-executor-core` (or the `local` / `remote` runtime variants).

### Basic Implementation

```java
@Executor(executorType = "inventory-agent")
public class InventoryAgent extends AbstractWorkflowExecutor {
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        String sku = (String) task.context().get("sku");
        // Logic to check inventory...
        return Uni.createFrom().item(NodeExecutionResult.success(task, Map.of("available", true)));
    }
}
```

## Data Transformation

Agents receive a **Context** (Map of variables) and return **Output** (Map of variables). 
- **Input**: Accessed via `task.context()`.
- **Output**: Provided via `NodeExecutionResult.success(task, outputMap)`.

The engine automatically merges the agent's output back into the global workflow context.

## Error Handling & Retries

The SDK client allows you to define retry policies for agent nodes:

```java
.execute("inventory-agent")
    .retry(RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Duration.ofSeconds(5))
        .build())
```

If an agent throws an exception or returns a failure, the engine will manage the retry logic according to this policy.
