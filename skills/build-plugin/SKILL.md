---
name: build-plugin
description: Build custom workflow-gamelan plugins to extend orchestration logic and execution behavior
metadata:
  short-description: Extend platform with plugins
  category: plugins
  difficulty: intermediate
---

# Build Plugin Skill

Create and deploy custom workflow-gamelan plugins to extend platform capabilities with custom orchestration logic.

## When to Use

- You need custom workflow logic
- You want to extend executor behavior
- You need custom compensation strategies
- You want to add workflow validation rules

## Supported Plugin Types

1. **Execution Interceptor** - Hook into node execution
2. **Compensation Plugin** - Custom compensation logic
3. **Validator Plugin** - Custom workflow validation
4. **Event Listener** - React to workflow events
5. **Service Registry** - Custom executor discovery

## Prerequisites

- Maven 3.8+
- JDK 17+
- gamelan-plugin-spi on classpath

## Steps

### 1. Create Plugin Project

```bash
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=gamelan-plugin-custom \
  -DpackageName=com.example.gamelan.plugin \
  -Dversion=1.0.0
```

### 2. Add Plugin Dependency

```xml
<dependency>
  <groupId>tech.kayys.gamelan</groupId>
  <artifactId>gamelan-plugin-spi</artifactId>
  <version>1.0.0</version>
  <scope>provided</scope>
</dependency>
```

### 3. Create Execution Interceptor Plugin

```java
import tech.kayys.gamelan.plugin.Plugin;
import tech.kayys.gamelan.plugin.PluginContext;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;
import tech.kayys.gamelan.plugin.PluginMetadata;

public class AuditingInterceptorPlugin 
    implements ExecutionInterceptorPlugin {
  
  private PluginContext context;
  
  @Override
  public void initialize(PluginContext context) 
      throws Exception {
    this.context = context;
    context.getLogger().info("Audit plugin initialized");
  }
  
  @Override
  public void beforeNodeExecution(
      WorkflowExecution execution,
      WorkflowNode node) throws Exception {
    
    context.getLogger().info(
      "Starting node: " + node.getId() + 
      " (workflow: " + execution.getWorkflowId() + 
      ", execution: " + execution.getId() + ")"
    );
    
    // Store audit event
    context.getAuditLog().record(AuditEvent.builder()
      .action("NODE_STARTED")
      .nodeId(node.getId())
      .executionId(execution.getId())
      .timestamp(Instant.now())
      .build()
    );
  }
  
  @Override
  public void afterNodeExecution(
      WorkflowExecution execution,
      WorkflowNode node,
      TaskResult result) throws Exception {
    
    context.getLogger().info(
      "Completed node: " + node.getId() + 
      " (status: " + result.getStatus() + ")"
    );
    
    context.getAuditLog().record(AuditEvent.builder()
      .action("NODE_COMPLETED")
      .nodeId(node.getId())
      .executionId(execution.getId())
      .status(result.getStatus().toString())
      .timestamp(Instant.now())
      .build()
    );
  }
  
  @Override
  public void onNodeFailure(
      WorkflowExecution execution,
      WorkflowNode node,
      Throwable error) throws Exception {
    
    context.getLogger().error(
      "Node failed: " + node.getId() + 
      " - " + error.getMessage()
    );
  }
  
  @Override
  public PluginMetadata getMetadata() {
    return new PluginMetadata(
      "auditing-interceptor",
      "Auditing Execution Interceptor",
      "1.0.0",
      "Your Name",
      "Logs all workflow node executions for audit trail",
      List.of("execution", "audit"),
      Map.of()
    );
  }
  
  @Override
  public void start() throws Exception {
    context.getLogger().info("Audit plugin started");
  }
  
  @Override
  public void stop() throws Exception {
    context.getLogger().info("Audit plugin stopped");
  }
}
```

### 4. Create Custom Compensation Plugin

```java
public class SmartCompensationPlugin 
    implements CompensationInterceptorPlugin {
  
  private PluginContext context;
  
  @Override
  public void initialize(PluginContext context) 
      throws Exception {
    this.context = context;
  }
  
  @Override
  public void beforeCompensation(
      WorkflowExecution execution,
      CompensationHandler handler) throws Exception {
    
    context.getLogger().info(
      "Starting compensation for node: " + 
      handler.getNodeId()
    );
  }
  
  @Override
  public void afterCompensation(
      WorkflowExecution execution,
      CompensationHandler handler,
      CompensationResult result) throws Exception {
    
    context.getLogger().info(
      "Compensation result: " + result.getMessage()
    );
  }
  
  @Override
  public PluginMetadata getMetadata() {
    return new PluginMetadata(
      "smart-compensation",
      "Smart Compensation Handler",
      "1.0.0",
      "Your Name",
      "Provides advanced compensation strategies",
      List.of("compensation", "saga"),
      Map.of()
    );
  }
  
  @Override
  public void start() throws Exception {}
  
  @Override
  public void stop() throws Exception {}
}
```

### 5. Create Event Listener Plugin

```java
public class MetricsPublisherPlugin 
    implements EventListenerPlugin {
  
  private PluginContext context;
  private MeterRegistry metrics;
  
  @Override
  public void initialize(PluginContext context) 
      throws Exception {
    this.context = context;
    this.metrics = context.getMetrics();
  }
  
  @Override
  public void onWorkflowEvent(WorkflowEvent event) 
      throws Exception {
    
    switch (event.getType()) {
      case WORKFLOW_STARTED:
        metrics.counter("workflow.started").increment();
        break;
      case WORKFLOW_COMPLETED:
        metrics.timer("workflow.duration").record(
          Duration.between(
            event.getStartTime(),
            event.getEndTime()
          )
        );
        metrics.counter("workflow.completed").increment();
        break;
      case WORKFLOW_FAILED:
        metrics.counter("workflow.failed").increment();
        break;
      case NODE_STARTED:
        metrics.counter("node.started",
          "node", event.getNodeId()
        ).increment();
        break;
      case NODE_COMPLETED:
        metrics.timer("node.duration",
          "node", event.getNodeId()
        ).record(Duration.between(
          event.getStartTime(),
          event.getEndTime()
        ));
        break;
    }
  }
  
  @Override
  public PluginMetadata getMetadata() {
    return new PluginMetadata(
      "metrics-publisher",
      "Metrics Publisher Plugin",
      "1.0.0",
      "Your Name",
      "Publishes workflow metrics",
      List.of("metrics", "observability"),
      Map.of()
    );
  }
  
  @Override
  public void start() throws Exception {}
  
  @Override
  public void stop() throws Exception {}
}
```

### 6. Register Plugin with ServiceLoader

Create `src/main/resources/META-INF/services/tech.kayys.gamelan.plugin.Plugin`:

```
com.example.gamelan.plugin.AuditingInterceptorPlugin
com.example.gamelan.plugin.SmartCompensationPlugin
com.example.gamelan.plugin.MetricsPublisherPlugin
```

### 7. Build Plugin JAR

```bash
mvn clean package -DskipTests
```

### 8. Deploy Plugin

```bash
# Copy to plugin directory
cp target/gamelan-plugin-custom-1.0.0.jar \
   /opt/gamelan/plugins/

# Restart gamelan service
systemctl restart gamelan
```

### 9. Verify Plugin Loaded

```bash
# Check logs
tail -f /var/log/gamelan/gamelan.log | grep plugin

# Or via CLI
gamelan plugin list
```

## Plugin Lifecycle

```
DISCOVERED → LOADED → INITIALIZED → ACTIVE ↔ RUNNING → STOPPED
```

### Lifecycle Hooks

```java
@Override
public void initialize(PluginContext context) throws Exception {
  // Called when plugin is loaded
  // Initialize resources, connect to services
}

@Override
public void start() throws Exception {
  // Called when plugin is activated
  // Begin processing events
}

@Override
public void stop() throws Exception {
  // Called when plugin is deactivated
  // Clean up resources
}
```

## Plugin Access to Platform Services

```java
public class AdvancedPlugin implements ExecutionInterceptorPlugin {
  
  private PluginContext context;
  
  @Override
  public void initialize(PluginContext context) 
      throws Exception {
    this.context = context;
    
    // Access platform services
    WorkflowDefinitionRegistry registry = 
      context.getWorkflowRegistry();
    
    ExecutorRegistry executors = 
      context.getExecutorRegistry();
    
    MeterRegistry metrics = 
      context.getMetrics();
    
    Logger logger = context.getLogger();
    
    // Access configuration
    Map<String, String> config = context.getConfig();
  }
}
```

## Plugin Configuration

```yaml
# application.properties
gamelan.plugins.enabled=true
gamelan.plugins.directory=/opt/gamelan/plugins

# Per-plugin configuration
gamelan.plugin.auditing-interceptor.enabled=true
gamelan.plugin.auditing-interceptor.log-level=INFO

gamelan.plugin.metrics-publisher.enabled=true
gamelan.plugin.metrics-publisher.batch-size=100
```

## Testing Plugins

```java
@Test
void testAuditingPlugin() throws Exception {
  AuditingInterceptorPlugin plugin = 
    new AuditingInterceptorPlugin();
  
  MockPluginContext context = 
    new MockPluginContext();
  
  plugin.initialize(context);
  
  WorkflowNode node = new WorkflowNode("test-node");
  plugin.beforeNodeExecution(null, node);
  
  verify(context.getAuditLog()).record(any());
}
```

## Plugin Examples

### Plugin 1: Rate Limiting

```java
public class RateLimitPlugin 
    implements ExecutionInterceptorPlugin {
  
  private RateLimiter limiter = 
    RateLimiter.create(10.0);  // 10 executions/sec
  
  @Override
  public void beforeNodeExecution(
      WorkflowExecution execution,
      WorkflowNode node) throws Exception {
    
    if (!limiter.tryAcquire()) {
      throw new RateLimitExceededException(
        "Too many executions"
      );
    }
  }
}
```

### Plugin 2: Custom Validator

```java
public class ValidationPlugin 
    implements WorkflowValidatorPlugin {
  
  @Override
  public void validate(WorkflowDefinition definition) 
      throws ValidationException {
    
    // Ensure all nodes have timeout
    definition.getNodes().forEach(node -> {
      if (node.getTimeout() == null) {
        throw new ValidationException(
          "Node " + node.getId() + 
          " missing timeout"
        );
      }
    });
    
    // Ensure compensation defined for critical nodes
    definition.getNodes()
      .filter(n -> n.isCritical())
      .forEach(n -> {
        if (!definition.hasCompensation(n.getId())) {
          throw new ValidationException(
            "Critical node " + n.getId() + 
            " missing compensation"
          );
        }
      });
  }
}
```

## Best Practices

1. **Non-Blocking** - Use async/reactive operations
2. **Error Handling** - Graceful degradation
3. **Logging** - Detailed logging for debugging
4. **Testing** - Comprehensive unit tests
5. **Documentation** - Clear usage examples
6. **Versioning** - Semantic versioning
7. **Performance** - Minimal overhead

## Troubleshooting

### Plugin Not Loaded
- Check META-INF/services entry
- Verify JAR is in plugin directory
- Check plugin manager logs

### Plugin Crash
- Review error logs
- Check for missing dependencies
- Verify lifecycle hooks

### Performance Issues
- Profile plugin code
- Check for blocking operations
- Optimize event processing

## See Also

- [Define Workflow](./define-workflow.md)
- [Execute Workflow](./execute-workflow.md)
- [Plugin Development Guide](../references/plugin-development.md)
