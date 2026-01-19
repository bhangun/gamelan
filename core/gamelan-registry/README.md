# Gamelan Registry Module

The Gamelan Registry module provides a robust, scalable, and resilient registry service for managing executor discovery and health monitoring in the Gamelan workflow engine.

## Features

- **Executor Discovery**: Dynamic registration and discovery of executors
- **Health Monitoring**: Continuous health checking with configurable thresholds
- **Advanced Selection Strategies**: Multiple algorithms for optimal executor selection
- **Persistence**: Support for both in-memory and Redis-based persistence
- **Metrics & Monitoring**: Built-in metrics collection with Micrometer integration
- **Resilience**: Circuit breaker, retry, and timeout mechanisms
- **Extensible Architecture**: Pluggable components for customization

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│  Executor       │    │  Registry        │    │  Persistence    │
│  Registration   │───▶│  Service         │───▶│  Layer          │
└─────────────────┘    │                  │    │                 │
                       │ • Health Monitor │    │ • In-Memory     │
┌─────────────────┐    │ • Selection      │    │ • Redis         │
│  Health Checks  │───▶│   Strategies     │    │                 │
└─────────────────┘    │ • Metrics        │    └─────────────────┘
                       │ • Resilience     │
                       └──────────────────┘
```

## Configuration

### Basic Setup

```java
@Inject
ExecutorRegistry registry;

// Register an executor
ExecutorInfo executor = new ExecutorInfo(
    "executor-1",
    "java-worker",
    CommunicationType.GRPC,
    "localhost:8080",
    Duration.ofSeconds(30),
    Map.of("region", "us-east-1", "capacity", "high")
);

registry.registerExecutor(executor)
    .subscribe().with(
        () -> System.out.println("Executor registered successfully"),
        error -> System.err.println("Registration failed: " + error)
    );
```

### Selection Strategies

The registry supports multiple executor selection strategies:

#### Round-Robin Strategy (Default)
```java
registry.setSelectionStrategy(new RoundRobinSelectionStrategy());
```

#### Random Strategy
```java
registry.setSelectionStrategy(new RandomSelectionStrategy());
```

#### Weighted Strategy (Load Balancing)
```java
registry.setSelectionStrategy(new WeightedSelectionStrategy());
```

### Health Monitoring

The registry automatically monitors executor health based on heartbeat signals:

```java
// Send heartbeat from executor
registry.heartbeat("executor-1");

// Check if executor is healthy
registry.isHealthy("executor-1")
    .subscribe().with(healthy -> {
        if (healthy) {
            System.out.println("Executor is healthy");
        } else {
            System.out.println("Executor is unhealthy");
        }
    });

// Get healthy executors only
registry.getHealthyExecutors()
    .subscribe().with(executors -> {
        System.out.println("Healthy executors: " + executors.size());
    });
```

### Persistence Configuration

The registry supports multiple persistence backends:

#### In-Memory (Default)
```java
// Uses InMemoryExecutorRepository by default
// Suitable for development and testing
```

#### Redis-Based
```java
// Configure RedisExecutorRepository in your CDI setup
// Enables persistence across application restarts
```

## Usage Examples

### 1. Register and Discover Executors

```java
// Register an executor
ExecutorInfo executor = new ExecutorInfo(
    "my-executor",
    "custom-type",
    CommunicationType.KAFKA,
    "kafka://broker:9092",
    Duration.ofSeconds(60),
    Map.of("tags", "production,high-priority")
);

registry.registerExecutor(executor)
    .subscribe().withVoid();

// Discover executor for a specific node
NodeId nodeId = NodeId.of("node-123");
registry.getExecutorForNode(nodeId)
    .subscribe().with(
        executorOpt -> {
            if (executorOpt.isPresent()) {
                System.out.println("Found executor: " + executorOpt.get().executorId());
            } else {
                System.out.println("No healthy executor available");
            }
        }
    );
```

### 2. Query Executors

```java
// Get all executors
registry.getAllExecutors()
    .subscribe().with(executors -> {
        System.out.println("Total executors: " + executors.size());
    });

// Get executors by type
registry.getExecutorsByType("java-worker")
    .subscribe().with(javaWorkers -> {
        System.out.println("Java workers: " + javaWorkers.size());
    });

// Get executors by communication type
registry.getExecutorsByCommunicationType(CommunicationType.GRPC)
    .subscribe().with(grpcExecutors -> {
        System.out.println("GRPC executors: " + grpcExecutors.size());
    });
```

### 3. Update Executor Metadata

```java
// Update executor metadata dynamically
Map<String, String> newMetadata = Map.of(
    "region", "us-west-2",
    "version", "1.2.3",
    "status", "scaling-up"
);

registry.updateExecutorMetadata("executor-1", newMetadata)
    .subscribe().withVoid();
```

### 4. Statistics and Monitoring

```java
// Get registry statistics
registry.getStatistics()
    .subscribe().with(stats -> {
        System.out.printf(
            "Total: %d, Healthy: %d, Unhealthy: %d%n",
            stats.totalExecutors(),
            stats.healthyExecutors(),
            stats.unhealthyExecutors()
        );
    });
```

## Metrics

The registry exposes the following metrics:

- `executor.registrations`: Number of executor registrations
- `executor.unregistrations`: Number of executor unregistrations  
- `executor.heartbeats`: Number of executor heartbeats received
- `executor.selections`: Number of executor selections made
- `executor.selection.duration`: Time taken to select an executor
- `executor.count`: Current number of registered executors

## Integration with Gamelan Engine

The registry integrates seamlessly with the Gamelan engine's workflow scheduler:

```java
// In your workflow scheduler
@Inject
ExecutorRegistry executorRegistry;

public Uni<Void> scheduleTask(NodeExecutionTask task) {
    return executorRegistry.getExecutorForNode(task.nodeId())
        .flatMap(executor -> taskDispatcher.dispatch(task, executor))
        .onFailure().recoverWithUni(error -> {
            // Handle dispatch failure
            return handleDispatchFailure(task, error);
        });
}
```

## Best Practices

1. **Health Checks**: Ensure executors send regular heartbeats to maintain health status
2. **Selection Strategy**: Choose the appropriate selection strategy based on your workload
3. **Persistence**: Use Redis persistence in production environments for data durability
4. **Monitoring**: Monitor registry metrics to detect and address issues early
5. **Configuration**: Adjust health thresholds based on your infrastructure characteristics

## Troubleshooting

### Common Issues

- **No Healthy Executors**: Check if executors are sending heartbeats regularly
- **High Latency**: Review selection strategy and consider load balancing approaches
- **Persistence Errors**: Verify Redis connectivity and configuration

### Debugging

Enable debug logging for detailed registry operations:

```properties
quarkus.log.category."tech.kayys.gamelan.registry".level=DEBUG
```

## Contributing

See the main Gamelan documentation for contribution guidelines.