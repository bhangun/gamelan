# Gamelan Engine API

This module defines the **core abstractions and interfaces** for the Gamelan Workflow Engine. It provides the foundational contracts that allow plugins, extensions, and external systems to integrate with the workflow orchestration system.

## Overview

The Engine API module serves as the **boundary** between the core engine implementation and external plugins. It defines:
- Domain objects and entities
- Service interfaces and contracts
- Repository abstractions
- Plugin and extension hooks
- Configuration interfaces
- Context objects for execution

This separation enables a clean architecture where the engine core can be extended without modifying its implementation.

## Key Features

- **Domain Model**: Core entities representing workflows, runs, nodes, and executions
- **Repository Abstractions**: Interfaces for persistence without tying to specific databases
- **Extension Points**: Well-defined hooks for plugins and custom implementations
- **Configuration API**: Extensible configuration management
- **Context Objects**: Execution contexts for workflows, nodes, and security
- **Plugin Architecture**: Clear contracts for developing plugins
- **Type Safety**: Strong typing for workflow definitions and runtime values

## Error Codes

Gamelan has a centralized error code registry in:

`core/gamelan-engine-spi/src/main/java/tech/kayys/gamelan/engine/error/ErrorCode.java`

Generate the docs:

```bash
./scripts/generate-error-codes.sh
```

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-engine-spi</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Core Concepts

### Domain Model

The Engine API defines the fundamental domain objects:

```
WorkflowDefinition
├── id: WorkflowDefinitionId
├── name: String
├── version: Int
├── nodes: Map<NodeId, NodeDefinition>
├── transitions: List<Transition>
└── metadata: Map<String, Any>

WorkflowRun (Aggregate Root)
├── id: WorkflowRunId
├── tenantId: TenantId
├── definitionId: WorkflowDefinitionId
├── status: RunStatus
├── variables: Map<String, Any>
├── nodeExecutions: Map<NodeId, NodeExecution>
└── executionPath: List<NodeId>

NodeExecution
├── nodeId: NodeId
├── status: NodeExecutionStatus
├── attempts: Int
├── startedAt: Instant
├── completedAt: Instant
├── result: ExecutionResult
└── error: ErrorInfo
```

### Key Interfaces

#### Repository Abstractions

**WorkflowDefinitionRepository**: Manage workflow definitions
```java
public interface WorkflowDefinitionRepository {
    Uni<WorkflowDefinition> findById(WorkflowDefinitionId id);
    Uni<List<WorkflowDefinition>> findByTenant(TenantId tenantId);
    Uni<Void> save(WorkflowDefinition definition);
}
```

**WorkflowRunRepository**: Manage workflow executions
```java
public interface WorkflowRunRepository {
    Uni<WorkflowRun> findById(WorkflowRunId id);
    Uni<List<WorkflowRun>> queryRuns(WorkflowQuery query);
    Uni<Void> save(WorkflowRun run);
    Uni<Void> update(WorkflowRunId id, WorkflowRun run);
}
```

#### Extension Points

**EngineExtension**: Hook into engine lifecycle
```java
public interface EngineExtension {
    void onEngineStartup(EngineContext context);
    void onEngineShutdown();
    void onWorkflowStarted(WorkflowRun run);
    void onWorkflowCompleted(WorkflowRun run);
    void onNodeStarted(WorkflowRun run, NodeExecution execution);
    void onNodeCompleted(WorkflowRun run, NodeExecution execution);
}
```

**PluginContext**: Access to engine services for plugins
```java
public interface PluginContext {
    <T> T getService(Class<T> serviceClass);
    Configuration getConfiguration();
    EventBus getEventBus();
    SecurityContext getSecurityContext();
    WorkflowContext getWorkflowContext();
}
```

#### Configuration

**Configuration**: Access to application properties
```java
public interface Configuration {
    String get(String key);
    String get(String key, String defaultValue);
    Integer getInt(String key, Integer defaultValue);
    Boolean getBoolean(String key, Boolean defaultValue);
    List<String> getList(String key);
}
```

#### Execution Context

**EngineContext**: Global engine state and services
```java
public interface EngineContext {
    TenantId getTenantId();
    Configuration getConfiguration();
    WorkflowDefinitionRegistry getDefinitionRegistry();
    ExtensionRegistry getExtensionRegistry();
    PluginContext getPluginContext();
}
```

**WorkflowContext**: Workflow execution state
```java
public interface WorkflowContext {
    WorkflowRunId getRunId();
    WorkflowDefinition getDefinition();
    Map<String, Object> getVariables();
    void setVariable(String key, Object value);
    NodeExecutionContext getCurrentNode();
}
```

**SecurityContext**: Security and authorization
```java
public interface SecurityContext {
    String getCurrentUserId();
    String getCurrentTenantId();
    List<String> getCurrentRoles();
    boolean hasPermission(String permission);
    String generateExecutionToken();
}
```

## Common Entity Types

### Value Objects

Value objects provide type-safe identifiers and enums:

```java
// Identifiers
WorkflowDefinitionId   // Unique workflow definition
WorkflowRunId          // Unique workflow execution
NodeId                 // Node identifier
TenantId               // Multi-tenant isolation
ExecutionToken         // Secure execution token

// Enumerations
RunStatus              // CREATED, RUNNING, COMPLETED, FAILED, CANCELLED, SUSPENDED
NodeExecutionStatus    // PENDING, RUNNING, SUCCESS, FAILURE, SKIPPED
ExecutionStatus        // SUCCESS, FAILURE, TIMEOUT, CANCELLED
```

### Data Transfer Objects (DTOs)

DTOs for API contracts:

```java
WorkflowDefinitionDto   // API representation of workflow
CreateRunRequest        // Create workflow execution
RunResponse            // Return workflow state
NodeExecutionUpdate    // Node execution status change
ExecutionHistoryEntry  // Historical execution record
```

## Plugin Development

### Creating a Plugin

To develop a plugin for Gamelan, implement the extension interfaces:

```java
public class MyCustomPlugin implements EngineExtension {
    
    @Override
    public void onEngineStartup(EngineContext context) {
        // Initialize plugin
        Configuration config = context.getConfiguration();
        String apiKey = config.get("my.plugin.api-key");
    }
    
    @Override
    public void onWorkflowStarted(WorkflowRun run) {
        // React to workflow start
        LOG.info("Workflow started: {}", run.id());
    }
    
    @Override
    public void onNodeCompleted(WorkflowRun run, NodeExecution execution) {
        // React to node completion
        LOG.info("Node completed: {} with status: {}", 
            execution.nodeId(), execution.status());
    }
}
```

### Accessing Engine Services

Plugins can access services through the context:

```java
public class IntegrationPlugin implements EngineExtension {
    
    @Override
    public void onEngineStartup(EngineContext context) {
        PluginContext pluginCtx = context.getPluginContext();
        
        // Get services
        WorkflowEngine engine = pluginCtx.getService(WorkflowEngine.class);
        EventBus eventBus = pluginCtx.getEventBus();
        
        // Subscribe to events
        eventBus.subscribe("workflow.completed", this::handleCompletion);
    }
    
    private void handleCompletion(WorkflowEvent event) {
        // Handle event
    }
}
```

## Extension Registry

Register extensions to hook into engine lifecycle:

```java
public interface ExtensionRegistry {
    void register(EngineExtension extension);
    void unregister(EngineExtension extension);
    List<EngineExtension> getExtensions();
    <T extends EngineExtension> List<T> getExtensions(Class<T> type);
}
```

## Service Interfaces

Key services exposed through the API:

### WorkflowEngine
Main orchestration service:

```java
public interface WorkflowEngine {
    Uni<WorkflowRun> createRun(CreateRunRequest request);
    Uni<WorkflowRun> startRun(WorkflowRunId runId);
    Uni<WorkflowRun> getRun(WorkflowRunId runId);
    Uni<Void> cancelRun(WorkflowRunId runId);
    Uni<List<WorkflowRun>> queryRuns(WorkflowQuery query);
}
```

### TaskDispatcher
Sends tasks to executors:

```java
public interface TaskDispatcher {
    Uni<Void> dispatchTask(NodeExecutionTask task);
    Uni<Void> dispatchTaskBatch(List<NodeExecutionTask> tasks);
    boolean canDispatch(String executorType);
}
```

### ExecutorRegistry
Manages registered executors:

```java
public interface ExecutorRegistry {
    void registerExecutor(ExecutorInfo executor);
    void unregisterExecutor(String executorId);
    Optional<ExecutorInfo> getExecutor(String executorType);
    List<ExecutorInfo> getExecutors();
}
```

## Best Practices

1. **Use Value Objects**: Always use typed identifiers instead of raw strings
   ```java
   // Good
   WorkflowRunId runId = WorkflowRunId.of("run-123");
   
   // Avoid
   String runId = "run-123";
   ```

2. **Implement Repositories**: Don't hardcode database queries; implement repository interfaces
   ```java
   public class SqlWorkflowRunRepository implements WorkflowRunRepository {
       // Your SQL implementation
   }
   ```

3. **Extend Through Extensions**: Use EngineExtension for lifecycle hooks rather than modifying core
   ```java
   @Override
   public void onWorkflowCompleted(WorkflowRun run) {
       // Custom logic without modifying engine
   }
   ```

4. **Immutable Domains**: Keep domain objects immutable; use builders for construction
   ```java
   WorkflowRun run = WorkflowRun.builder()
       .id(runId)
       .tenantId(tenantId)
       .status(RunStatus.CREATED)
       .build();
   ```

5. **Type-Safe Configuration**: Use Configuration interface for all settings
   ```java
   String timeout = config.get("workflow.timeout", "300");
   Integer maxRetries = config.getInt("executor.max-retries", 3);
   ```

## Integration Points

This API module integrates with:

- **gamelan-engine-core**: Implementation of API contracts
- **gamelan-plugin-api**: Plugin system built on extension interfaces
- **gamelan-sdk-executor-core**: Executor base classes and interfaces
- **gamelan-protocol-grpc**: Data mapping between API and gRPC messages
- **gamelan-protocol-kafka**: Data mapping between API and Kafka messages

## Dependency Graph

```
gamelan-engine-spi (this module)
  ├── [depends on core Gamelan API definitions]
  └── [no external dependencies except Mutiny/Quarkus]

gamelan-engine-core
  ├── [depends on gamelan-engine-spi]
  └── [implements all interfaces]

gamelan-plugin-api
  ├── [depends on gamelan-engine-spi]
  └── [extends extension system]
```

## Common Patterns

### Repository Pattern

```java
// Interface in API
public interface WorkflowRunRepository {
    Uni<WorkflowRun> findById(WorkflowRunId id);
    Uni<Void> save(WorkflowRun run);
}

// Implementation in core
@ApplicationScoped
public class PostgresWorkflowRunRepository implements WorkflowRunRepository {
    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id) {
        // SQL query implementation
    }
}
```

### Extension Pattern

```java
// Plugin defines extension
public class AuditPlugin implements EngineExtension {
    @Override
    public void onNodeCompleted(WorkflowRun run, NodeExecution exec) {
        auditLog.record(run.id(), exec.nodeId(), exec.status());
    }
}

// Register in configuration
extensionRegistry.register(new AuditPlugin());
```

### Service Lookup Pattern

```java
// Plugin accesses services
PluginContext ctx = engineContext.getPluginContext();
WorkflowEngine engine = ctx.getService(WorkflowEngine.class);
EventBus eventBus = ctx.getEventBus();
```

## Troubleshooting

### Extension Not Called

**Problem**: EngineExtension methods not being invoked
- Verify extension is registered with ExtensionRegistry
- Check extension doesn't throw exceptions
- Ensure lifecycle matches your use case

**Solution**:
```java
// Register explicitly
extensionRegistry.register(myExtension);
```

### Configuration Not Found

**Problem**: Configuration.get() returns null
- Verify property key is correct
- Check configuration source (properties file, environment, etc.)
- Use default values

**Solution**:
```java
String value = config.get("my.key", "default-value");
```

### Type Casting Issues

**Problem**: ClassCastException when using getService()
- Verify service type matches what you're expecting
- Check service is registered in PluginContext
- Use generics properly

**Solution**:
```java
WorkflowEngine engine = ctx.getService(WorkflowEngine.class);
// Don't cast to wrong type
```

## See Also

- **[gamelan-engine-core](../gamelan-engine-core/README.md)**: Implementation of these interfaces
- **[gamelan-plugin-api](../gamelan-plugin-api/README.md)**: Plugin system
- **[gamelan-engine](../gamelan-engine/README.md)**: Main engine module
