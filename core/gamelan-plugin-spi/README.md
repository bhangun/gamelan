# Gamelan Plugin API

This module provides the **plugin and extension system** for Gamelan. It enables developers to extend the workflow engine with custom functionality through plugins, services, and event handlers without modifying the core engine code.

## Overview

The Plugin API module offers:
- Plugin discovery and lifecycle management
- Extension registry and hooks
- Service locator pattern implementation
- Event bus for inter-component communication
- Scoped configuration for plugins
- Security context for plugins
- Runtime plugin context providing access to engine services

## Key Features

- **Auto-Discovery**: Automatically discover and load plugins from classpath
- **Lifecycle Hooks**: Hook into engine startup, shutdown, and workflow events
- **Service Registry**: Register and retrieve services dynamically
- **Event Bus**: Publish and subscribe to workflow events
- **Configuration Scoping**: Plugin-specific configuration with inheritance
- **Security Integration**: Access to tenant and user context
- **Dependency Injection**: Leverage Quarkus CDI for plugin dependencies

## Installation

Add the following dependency to your project:

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-plugin-api</artifactId>
    <version>${gamelan.version}</version>
</dependency>
```

## Plugin Development

### Creating a Basic Plugin

Plugins implement the `Plugin` interface and are discovered via the `@Plugin` annotation:

```java
@Plugin
@ApplicationScoped
public class LoggingPlugin implements EngineExtension {
    
    private static final Logger LOG = LoggerFactory.getLogger(LoggingPlugin.class);
    
    @Override
    public void onEngineStartup(EngineContext context) {
        LOG.info("LoggingPlugin starting up");
    }
    
    @Override
    public void onWorkflowStarted(WorkflowRun run) {
        LOG.info("Workflow {} started for tenant {}", 
            run.id(), run.tenantId());
    }
    
    @Override
    public void onNodeCompleted(WorkflowRun run, NodeExecution execution) {
        LOG.info("Node {} completed with status: {} (attempt: {})",
            execution.nodeId(), 
            execution.status(),
            execution.attempts());
    }
}
```

### Plugin Lifecycle

Plugins follow this lifecycle:

```
1. Discovery (startup)
   └─ Classpath scanning for @Plugin classes

2. Instantiation
   └─ CDI creates instance with dependencies injected

3. Initialization
   └─ onEngineStartup() called with EngineContext

4. Operation
   └─ Plugins receive callbacks for workflow events

5. Shutdown
   └─ onEngineShutdown() called on graceful shutdown
```

### Accessing Engine Services

Plugins can access engine services through the context:

```java
@Plugin
@ApplicationScoped
public class IntegrationPlugin implements EngineExtension {
    
    @Inject
    PluginContext pluginContext;
    
    @Override
    public void onEngineStartup(EngineContext context) {
        // Get configuration
        Configuration config = context.getConfiguration();
        String apiUrl = config.get("external.api.url");
        
        // Get services
        WorkflowEngine engine = pluginContext.getService(WorkflowEngine.class);
        EventBus eventBus = pluginContext.getEventBus();
        
        // Subscribe to events
        eventBus.subscribe(WorkflowEvent.COMPLETION, this::onWorkflowComplete);
    }
    
    private void onWorkflowComplete(WorkflowRun run) {
        // Call external service
        externalService.notifyCompletion(run.id());
    }
}
```

## Core Components

### 1. PluginContext

Provides access to engine services:

```java
public interface PluginContext {
    // Service lookup
    <T> T getService(Class<T> serviceClass);
    
    // Configuration access
    Configuration getConfiguration();
    
    // Event bus access
    EventBus getEventBus();
    
    // Security access
    SecurityContext getSecurityContext();
    
    // Workflow context
    WorkflowContext getWorkflowContext();
}
```

### 2. EventBus

Publish/subscribe event system:

```java
@ApplicationScoped
public interface EventBus {
    
    // Subscribe to events
    <T> void subscribe(String topic, Consumer<T> handler);
    <T> void subscribe(String topic, Class<T> eventClass, Consumer<T> handler);
    
    // Publish events
    void publish(String topic, Object event);
    
    // Async publication
    Uni<Void> publishAsync(String topic, Object event);
    
    // Unsubscribe
    void unsubscribe(String topic, Consumer<?> handler);
}
```

### 3. ServiceRegistry

Dynamic service registration and retrieval:

```java
@ApplicationScoped
public interface ServiceRegistry {
    
    // Register service
    <T> void register(Class<T> serviceClass, T instance);
    <T> void register(String name, Class<T> serviceClass, T instance);
    
    // Retrieve service
    <T> Optional<T> getService(Class<T> serviceClass);
    <T> Optional<T> getService(String name, Class<T> serviceClass);
    
    // List all services
    List<Object> getServices();
}
```

### 4. ExtensionRegistry

Manages extension lifecycle:

```java
@ApplicationScoped
public interface ExtensionRegistry {
    
    // Register extension
    void register(EngineExtension extension);
    
    // Retrieve extensions
    <T extends EngineExtension> List<T> getExtensions(Class<T> type);
    List<EngineExtension> getExtensions();
    
    // Trigger lifecycle events
    void triggerStartup(EngineContext context);
    void triggerShutdown();
}
```

### 5. Configuration Scoping

Plugin-specific configuration with fallback:

```java
@ApplicationScoped
public class ScopedConfiguration implements Configuration {
    
    private String pluginScope;  // e.g., "logging-plugin"
    
    @Override
    public String get(String key) {
        // Try plugin-scoped key first
        Optional<String> pluginValue = 
            getFromSource(pluginScope + "." + key);
        if (pluginValue.isPresent()) {
            return pluginValue.get();
        }
        
        // Fall back to global key
        return getFromSource(key).orElse(null);
    }
}
```

## Plugin Examples

### Example 1: Metrics Collection Plugin

```java
@Plugin
@ApplicationScoped
public class MetricsPlugin implements EngineExtension {
    
    private final MeterRegistry meterRegistry;
    
    @Inject
    public MetricsPlugin(MeterRegistry registry) {
        this.meterRegistry = registry;
    }
    
    @Override
    public void onWorkflowStarted(WorkflowRun run) {
        meterRegistry.counter("workflows.started").increment();
    }
    
    @Override
    public void onWorkflowCompleted(WorkflowRun run) {
        meterRegistry.counter("workflows.completed").increment();
        
        long duration = Duration.between(
            run.startedAt(), 
            run.completedAt()
        ).toMillis();
        
        meterRegistry.timer("workflow.execution.time")
            .record(duration, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void onNodeCompleted(WorkflowRun run, NodeExecution execution) {
        if (execution.isFailed()) {
            meterRegistry.counter("nodes.failed").increment();
        }
    }
}
```

### Example 2: Audit Logging Plugin

```java
@Plugin
@ApplicationScoped
public class AuditPlugin implements EngineExtension {
    
    @Inject
    AuditLogRepository auditLog;
    
    @Override
    public void onWorkflowStarted(WorkflowRun run) {
        auditLog.record(AuditEntry.builder()
            .action("WORKFLOW_STARTED")
            .runId(run.id())
            .tenantId(run.tenantId())
            .timestamp(Instant.now())
            .details(Map.of("definition", run.definitionId()))
            .build());
    }
    
    @Override
    public void onWorkflowCompleted(WorkflowRun run) {
        auditLog.record(AuditEntry.builder()
            .action("WORKFLOW_COMPLETED")
            .runId(run.id())
            .status(run.status().toString())
            .timestamp(Instant.now())
            .build());
    }
}
```

### Example 3: External Notification Plugin

```java
@Plugin
@ApplicationScoped
public class NotificationPlugin implements EngineExtension {
    
    @Inject
    NotificationService notificationService;
    
    @Inject
    PluginContext pluginContext;
    
    @Override
    public void onEngineStartup(EngineContext context) {
        Configuration config = context.getConfiguration();
        String webhookUrl = config.get("notifications.webhook-url");
        
        if (webhookUrl != null) {
            LOG.info("Notifications enabled: {}", webhookUrl);
        }
    }
    
    @Override
    public void onWorkflowCompleted(WorkflowRun run) {
        notificationService.sendAsync(Notification.builder()
            .type("workflow.completed")
            .runId(run.id())
            .status(run.status())
            .timestamp(Instant.now())
            .build())
            .subscribe().with(
                v -> LOG.debug("Notification sent for {}", run.id()),
                failure -> LOG.error("Failed to send notification", failure)
            );
    }
    
    @Override
    public void onNodeCompleted(WorkflowRun run, NodeExecution execution) {
        if (execution.isFailed()) {
            notificationService.sendAsync(Notification.builder()
                .type("node.failed")
                .runId(run.id())
                .nodeId(execution.nodeId())
                .error(execution.getError())
                .build()).subscribe().with(
                    v -> LOG.info("Alert sent for failed node"),
                    failure -> LOG.error("Failed to send alert", failure)
                );
        }
    }
}
```

### Example 4: Event Publishing Plugin

```java
@Plugin
@ApplicationScoped
public class EventPublishingPlugin implements EngineExtension {
    
    @Inject
    EventBus eventBus;
    
    @Override
    public void onNodeCompleted(WorkflowRun run, NodeExecution execution) {
        // Publish custom event
        NodeCompletedEvent event = new NodeCompletedEvent(
            run.id(),
            execution.nodeId(),
            execution.status(),
            execution.output()
        );
        
        eventBus.publishAsync("workflow.node.completed", event)
            .subscribe().with(
                v -> LOG.debug("Event published"),
                failure -> LOG.error("Failed to publish event", failure)
            );
    }
}
```

## Configuration for Plugins

### Plugin-Specific Configuration

```properties
# Global configuration
gamelan.engine.timeout=300

# Plugin-scoped configuration
my-plugin.timeout=600
my-plugin.enabled=true
my-plugin.api-key=${MY_PLUGIN_API_KEY}

# Nested plugin configuration
notifications.webhook-url=https://webhook.example.com
notifications.retry-attempts=3
```

### Loading Plugin Configuration

```java
@Plugin
@ApplicationScoped
public class MyPlugin implements EngineExtension {
    
    @Inject
    PluginContext context;
    
    @Override
    public void onEngineStartup(EngineContext engineContext) {
        Configuration config = engineContext.getConfiguration();
        
        // Get plugin-scoped config
        String timeout = config.get("my-plugin.timeout", "300");
        String apiKey = config.get("my-plugin.api-key");
        boolean enabled = config.getBoolean("my-plugin.enabled", true);
        
        LOG.info("Plugin configured: timeout={}, enabled={}", 
            timeout, enabled);
    }
}
```

## Event Topics

Common event topics published by the engine:

```
workflow.created         - Workflow created
workflow.started         - Workflow execution started
workflow.completed       - Workflow execution completed
workflow.failed          - Workflow execution failed
workflow.cancelled       - Workflow execution cancelled

node.started             - Node execution started
node.completed           - Node execution completed
node.failed              - Node execution failed
node.skipped             - Node skipped (conditional)

executor.registered      - Executor registered
executor.unregistered    - Executor unregistered
executor.failed          - Executor connectivity failure
```

## Best Practices

1. **Keep Plugins Lightweight**: Plugins execute in the critical path
   ```java
   // Good: minimal work in lifecycle hooks
   @Override
   public void onNodeCompleted(WorkflowRun run, NodeExecution exec) {
       metrics.increment("nodes.completed");  // O(1)
   }
   
   // Avoid: expensive operations
   @Override
   public void onNodeCompleted(WorkflowRun run, NodeExecution exec) {
       List<Data> data = expensiveQuery();  // Blocks execution
   }
   ```

2. **Use Async Operations**: Don't block the workflow thread
   ```java
   // Good: async notification
   notificationService.sendAsync(notification)
       .subscribe().with(
           success -> LOG.debug("Sent"),
           failure -> LOG.error("Failed to send")
       );
   
   // Avoid: blocking call
   notificationService.send(notification);  // Blocks!
   ```

3. **Error Handling**: Plugins should never crash the engine
   ```java
   @Override
   public void onWorkflowCompleted(WorkflowRun run) {
       try {
           doWork();
       } catch (Exception e) {
           LOG.error("Plugin error, continuing", e);
           // Don't rethrow
       }
   }
   ```

4. **Register Services Properly**: Use meaningful class types
   ```java
   // Good
   serviceRegistry.register(PaymentService.class, paymentService);
   PaymentService service = context.getService(PaymentService.class);
   
   // Avoid
   serviceRegistry.register(Object.class, paymentService);
   Object service = context.getService(Object.class);
   ```

5. **Scope Configuration**: Use plugin-specific prefixes
   ```properties
   # Good: scoped under plugin name
   payment-plugin.api-url=https://api.payment.com
   payment-plugin.timeout=30
   
   # Avoid: generic keys conflicting with other plugins
   api-url=https://api.payment.com
   ```

## Troubleshooting

### Plugin Not Loading

**Problem**: Plugin not discovered at startup
- Verify @Plugin annotation present
- Check @ApplicationScoped scope
- Ensure classpath scanning enabled

**Solution**:
```java
@Plugin  // Required
@ApplicationScoped  // CDI scope required
public class MyPlugin implements EngineExtension {
}
```

### Plugin Causing Performance Issues

**Problem**: Workflows slow or hanging
- Check for blocking operations in hooks
- Verify proper async usage
- Review error logs for exceptions

**Solution**:
```java
// Use async instead of blocking
@Override
public void onNodeCompleted(WorkflowRun run, NodeExecution exec) {
    externalService.notifyAsync(exec)  // Non-blocking
        .subscribe().with(
            v -> LOG.debug("Notified"),
            err -> LOG.error("Failed", err)
        );
}
```

### Service Not Found

**Problem**: getService() returns empty Optional
- Verify service registered correctly
- Check class type matches registration
- Ensure plugin runs after service registration

**Solution**:
```java
// Register service explicitly
serviceRegistry.register(MyService.class, new MyService());

// Or use factory pattern in startup hook
@Override
public void onEngineStartup(EngineContext context) {
    MyService service = context.getPluginContext()
        .getService(MyService.class);
}
```

## Integration with Other Modules

- **gamelan-engine-spi**: Defines extension interfaces
- **gamelan-engine-core**: Uses plugin system for extensions
- **gamelan-plugin-api**: This module provides implementation

## See Also

- **[gamelan-engine-spi](../gamelan-engine-spi/README.md)**: API contracts
- **[gamelan-engine-core](../gamelan-engine-core/README.md)**: Core engine
- **[gamelan-engine](../gamelan-engine/README.md)**: Main engine module
