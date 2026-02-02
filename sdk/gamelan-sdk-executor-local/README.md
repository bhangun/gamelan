# Gamelan SDK Executor - Local

This module provides a local, same-JVM implementation of the Gamelan Executor runtime. It allows you to run workflow executors (agents) directly within the same application process as the Gamelan engine, using Vert.x EventBus for high-performance communication.

## Key Features

- **Zero-Latency Communication**: Direct internal communication via Vert.x EventBus instead of network calls (gRPC/Kafka).
- **Auto-Discovery**: Automatic discovery and registration of executors using Quarkus CDI.
- **Unified API**: Uses the same `AbstractWorkflowExecutor` base class as remote executors, ensuring code portability.

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-sdk-executor-local</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Usage

Simply implement your executors and annotate them with `@Executor`:

```java
@ApplicationScoped
@Executor(executorType = "my-local-task")
public class MyLocalExecutor extends AbstractWorkflowExecutor {
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        // Your logic here
        return Uni.createFrom().item(NodeExecutionResult.success(task, Map.of("result", "done")));
    }
}
```

The `LocalExecutorRuntime` will automatically find and register these beans on startup.

## Documentation

For a detailed guide on implementing and using agents, see:
- **[AGENTS.md](file:///Users/bhangun/Workspace/workkayys/Products/Wayang/wayang-platform/workflow-gamelan/sdk/gamelan-sdk-client/AGENTS.md)**: Main guide for workflow agents.
