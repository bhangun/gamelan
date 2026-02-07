# Gamelan Executor Runtime

This module provides the **executor runtime environment** for running Gamelan tasks. It integrates with the core Gamelan engine to receive task assignments and report execution results, supporting distributed task processing across multiple executor instances.

## Overview

The Executor Runtime module bridges executors and the Gamelan workflow engine. It provides:
- Task receiving and dispatching
- Result collection and reporting
- Runtime lifecycle management
- Executor discovery and registration
- Health monitoring and heartbeat
- Support for multiple transport protocols
- Error handling and recovery

## Key Features

- **Multi-Transport Support**: Works with gRPC, Kafka, and REST transports
- **Auto-Discovery**: Automatically discovers and registers executor implementations
- **Reactive Execution**: Non-blocking task processing with Mutiny
- **Health Monitoring**: Continuous health checks and heartbeat signals
- **Result Tracking**: Track task execution and report results
- **Error Recovery**: Handle network failures and reconnection
- **Configuration**: Flexible runtime configuration via properties

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-executor-runtime</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Core Concepts

### Task Execution Model

```
Engine sends task
    ↓
ExecutorRuntime receives task
    ↓
Routes to appropriate executor
    ↓
Executor processes task
    ↓
Result sent back to engine
    ↓
Engine updates workflow state
```

### Runtime States

```
INITIALIZING
    └─ Loading configuration
    └─ Discovering executors

READY
    └─ Listening for tasks
    └─ Sending heartbeat

PROCESSING
    └─ Task assigned to executor
    └─ Executing business logic

REPORTING
    └─ Sending result to engine
    └─ Updating completion status

FAILED
    └─ Connection lost
    └─ Will attempt reconnect

SHUTDOWN
    └─ Graceful shutdown
    └─ Unregistering executors
```

## Runtime Configuration

### Application Properties

```properties
# Runtime Configuration
gamelan.executor.runtime.enabled=true
gamelan.executor.runtime.executor-id=executor-1
gamelan.executor.runtime.max-parallel-tasks=50

# Engine Connection
gamelan.executor.engine.url=http://localhost:8080
gamelan.executor.engine.connect-timeout=30
gamelan.executor.engine.request-timeout=300

# Heartbeat
gamelan.executor.heartbeat.enabled=true
gamelan.executor.heartbeat.interval=30
gamelan.executor.heartbeat.timeout=60

# Task Processing
gamelan.executor.task.queue-size=1000
gamelan.executor.task.dispatch-threads=10

# Error Handling
gamelan.executor.error.max-retries=3
gamelan.executor.error.backoff-initial=1000
gamelan.executor.error.backoff-multiplier=2

# Monitoring
gamelan.executor.metrics.enabled=true
gamelan.executor.metrics.export-interval=60
```

### Application YAML

```yaml
gamelan:
  executor:
    runtime:
      enabled: true
      executor-id: tax-processor-1
      max-parallel-tasks: 50
    
    engine:
      url: http://gamelan-engine:8080
      connect-timeout: 30
      request-timeout: 300
    
    heartbeat:
      enabled: true
      interval: 30
      timeout: 60
    
    task:
      queue-size: 1000
      dispatch-threads: 10
    
    error:
      max-retries: 3
      backoff-initial: 1000
      backoff-multiplier: 2
    
    metrics:
      enabled: true
      export-interval: 60
```

## Startup and Initialization

### Automatic Startup

The runtime starts automatically via the `@Startup` annotation:

```java
@Startup
@ApplicationScoped
public class ExecutorRuntime {
    
    @PostConstruct
    public void start() {
        // 1. Load configuration
        // 2. Discover executors
        // 3. Connect to engine
        // 4. Register executors
        // 5. Start listening for tasks
    }
}
```

### Manual Startup (if needed)

```java
@Inject
ExecutorRuntime runtime;

public void startProcessing() {
    runtime.start()
        .subscribe().with(
            v -> LOG.info("Runtime started"),
            failure -> LOG.error("Failed to start", failure)
        );
}
```

## Task Processing Flow

### 1. Receiving Tasks

```java
// Runtime receives task from engine via transport
NodeExecutionTask task = transport.receiveTask();

// Extract task details
String nodeId = task.nodeId().value();
Map<String, Object> input = task.context();

// Find matching executor
WorkflowExecutor executor = executorRegistry.findExecutor(nodeId);
```

### 2. Executing Task

```java
// Executor processes task
Uni<NodeExecutionResult> resultUni = executor.execute(task);

// Handle result
resultUni.subscribe().with(
    result -> handleSuccess(result),
    failure -> handleFailure(task, failure)
);
```

### 3. Reporting Result

```java
// Send result back to engine
transport.reportResult(result)
    .subscribe().with(
        v -> LOG.info("Result reported for {}", task.nodeId()),
        failure -> LOG.error("Failed to report result", failure)
    );

// Update metrics
metrics.recordExecution(
    task.nodeId(),
    result.status(),
    result.executionTime()
);
```

## Executor Management

### Executor Registry

The runtime maintains a registry of available executors:

```java
@ApplicationScoped
public class ExecutorRegistry {
    
    // Register executor
    public void register(WorkflowExecutor executor) {
        String type = executor.getMetadata().executorType();
        registry.put(type, executor);
    }
    
    // Find executor by type
    public Optional<WorkflowExecutor> findExecutor(String type) {
        return Optional.ofNullable(registry.get(type));
    }
    
    // Get all executors
    public Collection<WorkflowExecutor> getAll() {
        return registry.values();
    }
}
```

### Discovery and Registration

Executors are discovered and registered automatically:

```java
// 1. Discovery
List<WorkflowExecutor> discovered = 
    discoverer.discoverExecutors();

// 2. Registration with runtime
executors.forEach(executor -> {
    runtime.registerExecutor(executor);
});

// 3. Registration with engine
transport.registerExecutors(executors)
    .subscribe().with(
        v -> LOG.info("Registered {} executors", executors.size()),
        failure -> LOG.error("Registration failed", failure)
    );
```

## Health Monitoring

### Heartbeat Mechanism

The runtime sends periodic heartbeats to the engine:

```java
@ApplicationScoped
public class HeartbeatManager {
    
    @Scheduled(every = "30s")
    public void sendHeartbeat() {
        transport.sendHeartbeat()
            .subscribe().with(
                v -> LOG.debug("Heartbeat sent"),
                failure -> {
                    LOG.error("Heartbeat failed", failure);
                    handleConnectionLoss();
                }
            );
    }
}
```

### Health Status

Monitor runtime health:

```java
@GET
@Path("/health")
public HealthStatus getHealth() {
    return HealthStatus.builder()
        .status(runtime.isRunning() ? "UP" : "DOWN")
        .executors(executorRegistry.size())
        .activeTasks(taskDispatcher.getActiveTasks())
        .lastHeartbeat(heartbeatManager.getLastHeartbeat())
        .build();
}
```

## Error Handling and Recovery

### Connection Failures

Automatic reconnection on engine disconnect:

```java
public void handleConnectionLoss() {
    LOG.warn("Connection lost, attempting reconnect...");
    
    reconnectStrategy.reconnect()
        .withBackOff(Duration.ofSeconds(1))
        .withMaxRetries(10)
        .subscribe().with(
            v -> LOG.info("Reconnected to engine"),
            failure -> LOG.error("Reconnection failed", failure)
        );
}
```

### Task Execution Failures

Distinguish between business and technical failures:

```java
private void handleExecutionFailure(
    NodeExecutionTask task, Throwable error) {
    
    if (isBusinessException(error)) {
        // Expected failure - report to engine
        reportFailureResult(task, error);
    } else {
        // Technical error - may retry
        handleTechnicalError(task, error);
    }
}
```

## Performance Tuning

### Parallel Task Processing

Configure concurrency level:

```properties
# Allow 50 tasks to execute in parallel
gamelan.executor.task.dispatch-threads=50
gamelan.executor.runtime.max-parallel-tasks=50
```

### Queue Sizing

Balance memory and throughput:

```properties
# Queue up to 5000 pending tasks
gamelan.executor.task.queue-size=5000
```

### Batching

Group result reporting for efficiency:

```properties
# Report results in batches of 100
gamelan.executor.result.batch-size=100
gamelan.executor.result.batch-timeout=5000
```

## Monitoring and Observability

### Metrics

Export executor runtime metrics:

```properties
# Enable metrics
gamelan.executor.metrics.enabled=true

# Export to Prometheus
quarkus.micrometer.export.prometheus.enabled=true
```

Available metrics:
- `executor.tasks.total` - Total tasks received
- `executor.tasks.completed` - Successfully completed
- `executor.tasks.failed` - Failed execution
- `executor.tasks.duration` - Execution time
- `executor.registry.size` - Number of registered executors
- `executor.queue.size` - Pending tasks in queue

### Logging

Configure logging for runtime:

```properties
# Runtime logging
quarkus.log.category."gamelan.executor.runtime".level=DEBUG

# Task logging
quarkus.log.category."gamelan.executor.task".level=INFO

# Error logging
quarkus.log.category."gamelan.executor.error".level=WARN
```

## Best Practices

1. **Configure Appropriate Concurrency**:
   ```properties
   # I/O-bound: cores * 2-3
   # CPU-bound: cores
   gamelan.executor.runtime.max-parallel-tasks=50
   ```

2. **Monitor Health Status**:
   ```bash
   curl http://localhost:8080/health
   ```

3. **Handle Errors Gracefully**:
   ```java
   // Always return result, even on errors
   return Uni.createFrom().item(
       NodeExecutionResult.failure(task, "Error occurred")
   );
   ```

4. **Use Async I/O**:
   ```java
   // Good: non-blocking
   return externalService.callAsync();
   
   // Avoid: blocking
   return externalService.callSync();
   ```

5. **Configure Timeouts**:
   ```properties
   gamelan.executor.engine.request-timeout=300
   ```

## Troubleshooting

### Runtime Won't Start

**Problem**: Executor runtime fails during startup
- Check engine connectivity
- Verify configuration is correct
- Review startup logs for errors

**Solution**:
```bash
# Check engine is running
curl http://gamelan-engine:8080/health

# Increase log level
export QUARKUS_LOG_LEVEL=DEBUG
```

### Tasks Not Being Received

**Problem**: Runtime started but no tasks assigned
- Verify executor registration succeeded
- Check engine logs for errors
- Ensure executor type matches workflow

**Solution**:
```bash
# Check if registered
curl http://localhost:8080/health

# Verify executor type
# Matches workflow node executor type
```

### Heartbeat Failures

**Problem**: Engine marks executor as dead
- Check network connectivity
- Increase heartbeat timeout
- Verify engine reachability

**Solution**:
```properties
gamelan.executor.heartbeat.timeout=120
gamelan.executor.engine.connect-timeout=60
```

## See Also

- **[gamelan-executor-registry](../gamelan-executor-registry/README.md)**: Executor registry management
- **[gamelan-executor-runtime-core](../gamelan-executor-runtime-core/README.md)**: Core runtime abstractions
- **[gamelan-sdk-executor-local](../../sdk/gamelan-sdk-executor-local/README.md)**: Local executor SDK
- **[gamelan-sdk-executor-remote](../../sdk/gamelan-sdk-executor-remote/README.md)**: Remote executor SDK
