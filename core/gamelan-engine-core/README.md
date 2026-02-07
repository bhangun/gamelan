# Gamelan Engine Core

This module provides the **concrete implementation** of the Gamelan Workflow Engine. It contains the core orchestration logic, state management, event sourcing, persistence, and task scheduling that power workflow execution.

## Overview

The Engine Core module implements the contracts defined in `gamelan-engine-spi` and provides:
- Complete workflow execution engine
- Event sourcing and state management
- Plugin system and extensions
- Workflow definition registry
- Execution scheduler and dispatcher
- Saga pattern support for distributed transactions
- Metrics collection and observability

## Key Features

- **Reactive Execution**: Built on Quarkus and Mutiny for non-blocking workflow orchestration
- **Event Sourcing**: Complete audit trail with event replay capability
- **CQRS Pattern**: Optimized command and query paths
- **State Machine**: Robust workflow state management with validated transitions
- **Plugin System**: Extensible architecture with lifecycle hooks
- **Saga Support**: Automated compensation for distributed transactions
- **Metrics**: OpenTelemetry integration for observability
- **Configuration Management**: Composite configuration from multiple sources

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-engine-core</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Architecture

### Core Components

#### 1. DefaultWorkflowEngine

The main orchestration engine implementing `WorkflowEngine`:

```java
@ApplicationScoped
public class DefaultWorkflowEngine implements WorkflowEngine {
    
    @Override
    public Uni<WorkflowRun> createRun(CreateRunRequest request) {
        // Create new workflow execution
        // Validate workflow definition
        // Initialize execution state
        // Publish RUN_CREATED event
    }
    
    @Override
    public Uni<WorkflowRun> startRun(WorkflowRunId runId) {
        // Load workflow run
        // Transition to RUNNING state
        // Schedule initial nodes
        // Dispatch tasks to executors
    }
}
```

#### 2. Workflow Definition Registry

Manages registered workflow definitions:

```java
@ApplicationScoped
public class WorkflowDefinitionRegistry {
    
    public void register(WorkflowDefinition definition) {
        // Register definition
        // Validate transitions
        // Index for quick lookup
    }
    
    public Optional<WorkflowDefinition> getDefinition(WorkflowDefinitionId id) {
        // Retrieve definition
    }
}
```

#### 3. Event Store

Event sourcing implementation for complete audit trail:

```java
@ApplicationScoped
public class EventStore {
    
    public Uni<Void> append(WorkflowId id, WorkflowEvent event) {
        // Append immutable event
        // Update snapshots
        // Trigger projections
    }
    
    public Uni<List<WorkflowEvent>> getEvents(WorkflowId id) {
        // Retrieve event history
        // Support filtering by type
    }
}
```

#### 4. Plugin System

Plugin discovery and lifecycle management:

```java
@ApplicationScoped
public class PluginManager {
    
    public void discoverAndLoadPlugins() {
        // Scan classpath for @Plugin annotated classes
        // Instantiate plugins
        // Register in ExtensionRegistry
        // Call onEngineStartup hooks
    }
    
    public void executeLifecycleHooks(String phase, Object... args) {
        // Execute all registered hooks for phase
        // Handle exceptions gracefully
    }
}
```

#### 5. Configuration Management

Composite configuration from multiple sources:

```java
@ApplicationScoped
public class CompositeConfiguration implements Configuration {
    
    // Merge configurations from:
    // - application.properties
    // - application-{env}.properties
    // - Environment variables
    // - System properties
    // - Plugin configurations
}
```

#### 6. Task Scheduler

Schedules tasks for execution by executors:

```java
@ApplicationScoped
public class TaskScheduler {
    
    public Uni<Void> scheduleTask(NodeExecutionTask task) {
        // Determine task priority
        // Calculate delay if needed
        // Queue for dispatcher
        // Handle retries with backoff
    }
    
    public Uni<Void> handleTaskCompletion(TaskResult result) {
        // Update node execution status
        // Evaluate transitions
        // Schedule next nodes
        // Handle failures and retries
    }
}
```

#### 7. Compensation Coordinator (Saga Pattern)

Handles distributed transaction compensation:

```java
@ApplicationScoped
public class CompensationCoordinator {
    
    public Uni<Void> compensate(WorkflowRunId runId) {
        // Retrieve executed nodes in reverse order
        // Invoke compensation for each node
        // Handle compensation failures
        // Update run state
    }
}
```

### Execution Flow

```
Client Request
    ↓
CreateRun Handler
    ├─ Validate workflow definition
    ├─ Create WorkflowRun aggregate
    ├─ Append RUN_CREATED event
    └─ Return WorkflowRun
    
StartRun Handler
    ├─ Load WorkflowRun
    ├─ Transition to RUNNING
    ├─ Append RUN_STARTED event
    ├─ Get initial nodes
    └─ Schedule tasks
    
Task Execution
    ├─ Task dispatched to executor
    ├─ Executor processes task
    ├─ Executor reports result
    
TaskResult Handler
    ├─ Validate result token
    ├─ Update NodeExecution
    ├─ Append NODE_COMPLETED event
    ├─ Evaluate transitions
    ├─ Schedule next nodes
    └─ Check for completion
```

## Key Classes

### WorkflowRun (Aggregate Root)

```java
public class WorkflowRun {
    private WorkflowRunId id;
    private TenantId tenantId;
    private WorkflowDefinitionId definitionId;
    private RunStatus status;
    private Map<String, Object> variables;
    private Map<NodeId, NodeExecution> nodeExecutions;
    private List<WorkflowEvent> events;  // Event sourcing
    
    public void startExecution() {
        // Validate preconditions
        this.status = RunStatus.RUNNING;
        this.events.add(new RunStartedEvent(...));
    }
    
    public void completeNode(NodeId nodeId, ExecutionResult result) {
        // Update node execution
        // Evaluate next transitions
        // Append NodeCompletedEvent
    }
}
```

### NodeExecution

```java
public class NodeExecution {
    private NodeId nodeId;
    private NodeExecutionStatus status;
    private int attempts;
    private Instant startedAt;
    private Instant completedAt;
    private Map<String, Object> output;
    private ErrorInfo error;
    
    public void markSuccess(Map<String, Object> result) {
        this.status = NodeExecutionStatus.SUCCESS;
        this.output = result;
        this.completedAt = Instant.now();
    }
    
    public void markFailure(ErrorInfo error, boolean retryable) {
        this.status = NodeExecutionStatus.FAILURE;
        this.error = error;
        this.completedAt = Instant.now();
        // Schedule retry if retryable
    }
}
```

### WorkflowEvent

Base class for event sourcing:

```java
public abstract class WorkflowEvent {
    protected WorkflowRunId runId;
    protected Instant timestamp;
    protected String actorId;
    
    public static class RunCreatedEvent extends WorkflowEvent { ... }
    public static class RunStartedEvent extends WorkflowEvent { ... }
    public static class NodeStartedEvent extends WorkflowEvent { ... }
    public static class NodeCompletedEvent extends WorkflowEvent { ... }
    public static class CompensationTriggeredEvent extends WorkflowEvent { ... }
    // ... more event types
}
```

## Configuration

### Application Properties

```properties
# Engine Configuration
gamelan.engine.name=gamelan-engine
gamelan.engine.version=1.0.0

# Event Store
gamelan.event-store.enabled=true
gamelan.event-store.snapshot-frequency=100

# Scheduler
gamelan.scheduler.thread-pool-size=10
gamelan.scheduler.queue-size=1000

# Dispatcher
gamelan.dispatcher.default-timeout=300
gamelan.dispatcher.max-retries=3
gamelan.dispatcher.backoff-multiplier=2

# Saga/Compensation
gamelan.saga.enabled=true
gamelan.saga.compensation-timeout=600

# Plugin System
gamelan.plugin.auto-discovery=true
gamelan.plugin.scan-classpath=true

# Metrics
gamelan.metrics.enabled=true
gamelan.metrics.export-interval=60
```

## Usage Examples

### Running the Engine

```java
@Inject
WorkflowEngine engine;

public void executeWorkflow() {
    // Create a workflow run
    CreateRunRequest request = CreateRunRequest.builder()
        .tenantId(TenantId.of("tenant-123"))
        .workflowDefinitionId(WorkflowDefinitionId.of("order-processing"))
        .inputs(Map.of(
            "orderId", "ORD-001",
            "amount", 100.0
        ))
        .build();
    
    engine.createRun(request)
        .flatMap(run -> engine.startRun(run.id()))
        .subscribe().with(
            run -> LOG.info("Workflow started: {}", run.id()),
            failure -> LOG.error("Failed to start workflow", failure)
        );
}
```

### Listening to Workflow Events

```java
@Inject
ExtensionRegistry extensionRegistry;

public void setupAuditLogging() {
    extensionRegistry.register(new EngineExtension() {
        @Override
        public void onWorkflowCompleted(WorkflowRun run) {
            LOG.info("Workflow completed: {} with status: {}", 
                run.id(), run.status());
            auditLog.record("WORKFLOW_COMPLETED", run.id());
        }
        
        @Override
        public void onNodeCompleted(WorkflowRun run, NodeExecution exec) {
            LOG.debug("Node {} completed with status: {}", 
                exec.nodeId(), exec.status());
        }
    });
}
```

### Adding Custom Plugins

```java
@Plugin
@ApplicationScoped
public class NotificationPlugin implements EngineExtension {
    
    @Inject
    EmailService emailService;
    
    @Override
    public void onWorkflowCompleted(WorkflowRun run) {
        // Send completion notification
        String recipient = (String) run.variables().get("notificationEmail");
        if (recipient != null) {
            emailService.sendCompletion(run.id(), recipient);
        }
    }
}
```

## Best Practices

1. **Use Event Sourcing for Audit**: Always append events for changes
   ```java
   workflowRun.startExecution();  // Appends RUN_STARTED event
   ```

2. **Implement Compensation**: For multi-step workflows, define compensation
   ```java
   node.setCompensation(() -> refundPayment());
   ```

3. **Configure Appropriate Timeouts**: Balance responsiveness with reliability
   ```properties
   gamelan.dispatcher.default-timeout=300  # 5 minutes for long operations
   ```

4. **Monitor Metrics**: Export metrics for observability
   ```properties
   gamelan.metrics.enabled=true
   ```

5. **Test with Plugin System**: Use extensions for testing
   ```java
   @Test
   public void testWorkflowWithMocks() {
       extRegistry.register(new MockDispatcherPlugin());
       engine.createRun(...);
   }
   ```

## Performance Considerations

1. **Event Snapshots**: Configure snapshot frequency to balance storage and replay time
   ```properties
   gamelan.event-store.snapshot-frequency=100  # Every 100 events
   ```

2. **Thread Pool Sizing**: Configure scheduler thread pool based on concurrency needs
   ```properties
   gamelan.scheduler.thread-pool-size=20  # For 20 concurrent workflows
   ```

3. **Queue Size**: Prevent memory exhaustion with bounded queue
   ```properties
   gamelan.scheduler.queue-size=5000
   ```

4. **Connection Pooling**: Ensure DB connection pool is appropriately sized
   ```properties
   quarkus.datasource.max-size=20
   ```

## Troubleshooting

### Workflows Not Progressing

**Problem**: Workflows stuck in RUNNING state
- Check task dispatcher connectivity to executors
- Verify executor registration in ExecutorRegistry
- Review error logs for task failures

**Solution**:
```java
// Check registered executors
List<ExecutorInfo> executors = executorRegistry.getExecutors();
LOG.info("Registered executors: {}", executors);

// Check pending tasks
List<NodeExecutionTask> pending = taskScheduler.getPendingTasks();
LOG.info("Pending tasks: {}", pending.size());
```

### High Memory Usage

**Problem**: Engine consuming excessive memory
- Check event store snapshot configuration
- Reduce queue size if not needed
- Monitor active workflow count

**Solution**:
```properties
# Enable event snapshots
gamelan.event-store.snapshot-frequency=50

# Reduce queue size
gamelan.scheduler.queue-size=1000
```

### Plugin Not Loading

**Problem**: Custom plugin not being discovered
- Verify @Plugin annotation on class
- Check @ApplicationScoped scope
- Ensure classpath scanning is enabled

**Solution**:
```java
@Plugin
@ApplicationScoped
public class MyPlugin implements EngineExtension {
    // Plugin code
}

// Or register manually
extensionRegistry.register(new MyPlugin());
```

## Integration with Other Modules

- **gamelan-engine-spi**: Implements all contracts
- **gamelan-plugin-api**: Uses plugin system
- **gamelan-protocol-grpc**: Maps domain objects to gRPC messages
- **gamelan-protocol-kafka**: Maps domain objects to Kafka messages
- **gamelan-sdk-executor-core**: Receives task results

## See Also

- **[gamelan-engine-spi](../gamelan-engine-spi/README.md)**: API contracts
- **[gamelan-plugin-api](../gamelan-plugin-api/README.md)**: Plugin development
- **[gamelan-engine](../gamelan-engine/README.md)**: Main engine module
- **[gamelan-executor-registry](../gamelan-executor-registry/README.md)**: Executor management
