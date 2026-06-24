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
    private Instant retryAt;
    private Map<String, Object> output;
    private ErrorInfo error;
    
    public void markSuccess(Map<String, Object> result) {
        this.status = NodeExecutionStatus.SUCCESS;
        this.output = result;
        this.completedAt = Instant.now();
    }
    
    public void markFailure(ErrorInfo error, boolean retryable) {
        this.error = error;
        if (retryable) {
            this.status = NodeExecutionStatus.RETRYING;
            this.retryAt = Instant.now().plus(retryPolicy.calculateDelay(attempts));
        } else {
            this.status = NodeExecutionStatus.FAILURE;
            this.completedAt = Instant.now();
        }
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

# Execution Interceptor Policy
# Default false keeps interceptor failures isolated; set true for strict
# profiles where beforeExecution hooks enforce auth, quota, or policy gates.
gamelan.engine.execution.interceptors.before.fail-on-error=false

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

2. **Keep Event Publication Durable And Validated**: Persist workflow/retry events before notifying interceptors. Interceptor failures must be isolated so audit and retry wake-up still happen. At orchestration ingress, validate run update IDs and decode executor result events into concrete, versioned payload types before mutating run state. Distributed executor callbacks should carry tenant context and call the tenant-aware workflow feedback APIs so colliding run IDs cannot cross tenant boundaries. Lifecycle, retry, recovery, and orchestrator self-wake paths should emit the tenant-aware `WorkflowRunUpdateEvent` payload through `WorkflowRunWakeupPublisher`; legacy string run IDs remain supported for backward compatibility. The default publisher records workflow, system, batch, retry, interceptor, persistence, and wake-up diagnostics; it falls back to the local event bus when a configured wake-up publisher fails and keeps the persisted event as the source of truth. The default Vert.x publisher coalesces failed wake-ups by tenant/run, keeps the latest reason, guards in-flight redelivery, clears stale buffered entries after successful direct delivery, exposes pending snapshots plus low-cardinality delivery diagnostics, and retries using `gamelan.workflow.wakeup.*` limits; durable profiles can replace the publisher with a persistent outbox. All persisted lifecycle status changes should publish a run update after storage/history writes. Idempotent lifecycle retries, including repeated active `startRun`/`resumeRun` calls and same-terminal `cancelRun`/`completeRun` calls, should still emit a wake-up without mutating storage, so API retries can recover missed drive events.
   ```java
   eventPublisher.publishRetry(runId, tenantId, nodeId);  // Appends retry event and tenant-aware wake-up
   ```

3. **Honor Retry Due Time**: Delayed retries stay in `RETRYING` with `retryAt`; planners must not dispatch them until the retry manager wakes the run at or after that time. Retry managers keep due entries until the wake-up event is published successfully, giving retry wake-ups at-least-once delivery. Retry entry encoding carries tenant id and retry attempt when available, and remains backward-compatible with legacy run/node entries already stored in local memory or Redis. Use `gamelan.retry.scan-interval`, `gamelan.retry.redis.batch-size`, and `gamelan.retry.redis.claim-ttl` to tune retry queue pressure; Redis retry atomically preserves the earliest scheduled score per encoded retry attempt, skips duplicate scheduling while an entry is already claimed in the processing set, drains due entries into that processing set, acks after publish, requeues on publish failure, and restores expired claims after runtime crashes.
   ```java
   nodeExecution.isRetryDue(clock.instant());
   ```

4. **Run Recovery Sweeps**: Enable `gamelan.recovery.*` in production profiles so running runs are scanned through the configured persistence strategy. The sweeper snapshots active-run pages before mutating any run, de-duplicates overlapping page results by tenant and run id, wakes due retries if a retry event was missed, backfills future delayed retry wake-ups that may have been lost, treats wake-up publication failures as repairable sweep warnings, reaps only in-flight nodes whose explicit node timeout plus grace has elapsed, isolates per-run failures, and recovers runs in bounded chunks controlled by `gamelan.recovery.max-concurrent-runs`.
   ```properties
   gamelan.recovery.enabled=true
   gamelan.recovery.scan-interval=30s
   gamelan.recovery.page-size=100
   gamelan.recovery.max-concurrent-runs=4
   gamelan.recovery.timeout-grace=30s
   ```

5. **Keep Planner Outputs Immutable And Normalized**: `ExecutionPlan` defensively snapshots ready nodes and outputs. The default planner also de-duplicates scheduler output, ignores nodes that were not computed as ready, and appends omitted ready nodes so DAG scheduler extensions cannot accidentally stall ready work.

6. **Coalesce And Batch Run Drive Events**: Treat run update events as level-triggered signals, not a one-event-one-drive contract. Only one drive cycle should be active per run; duplicate events should collapse into a single follow-up cycle, planner ready nodes should be de-duplicated and validated against the workflow definition before dispatch, large fan-outs should be capped with `gamelan.orchestrator.max-ready-nodes-per-cycle`, and in-flight dispatches should be bounded with `gamelan.orchestrator.max-concurrent-dispatches` to reduce redundant planning, locking, and dispatch bursts. The default fail-fast executor policy reserves a node before executor selection so stale planner output skips registry and token work; `gamelan.orchestrator.no-executor-policy=wait` resolves first so ready nodes remain pending while executors are unavailable.

7. **Implement Compensation**: For multi-step workflows, define compensation
   ```java
   node.setCompensation(() -> refundPayment());
   ```

8. **Configure Appropriate Timeouts**: Balance responsiveness with reliability
   ```properties
   gamelan.dispatcher.default-timeout=300  # 5 minutes for long operations
   ```

9. **Monitor Metrics**: Export metrics for observability
   ```properties
   gamelan.metrics.enabled=true
   ```

10. **Test with Plugin System**: Use extensions for testing
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

2. **Definition Hot Paths**: Keep workflow definitions immutable and index node lookups inside runtime aggregates so wide DAGs do not repeatedly scan node lists during scheduling, retry, replay, restore, and compensation.

3. **Thread Pool Sizing**: Configure scheduler thread pool based on concurrency needs
   ```properties
   gamelan.scheduler.thread-pool-size=20  # For 20 concurrent workflows
   ```

4. **Queue Size**: Prevent memory exhaustion with bounded queue
   ```properties
   gamelan.scheduler.queue-size=5000
   ```

5. **Connection Pooling**: Ensure DB connection pool is appropriately sized
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
- **gamelan-plugin-spi**: Uses plugin system
- **gamelan-protocol-grpc**: Maps domain objects to gRPC messages
- **gamelan-protocol-kafka**: Maps domain objects to Kafka messages
- **gamelan-sdk-executor-core**: Receives task results

## See Also

- **[gamelan-engine-spi](../gamelan-engine-spi/README.md)**: API contracts
- **[gamelan-plugin-spi](../gamelan-plugin-spi/README.md)**: Plugin development
- **[gamelan-engine](../gamelan-engine/README.md)**: Main engine module
- **[gamelan-executor-registry](../gamelan-executor-registry/README.md)**: Executor management
