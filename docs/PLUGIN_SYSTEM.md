# Gamelan Plugin System

## Overview

The Gamelan Plugin System allows you to extend the workflow engine with custom functionality through well-defined extension points.

## Quick Start

### 1. Add Plugin API Dependency

```xml
<dependency>
    <groupId>tech.kayys.gamelan</groupId>
    <artifactId>gamelan-plugin-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### 2. Create Your Plugin

```java
package com.example;

import tech.kayys.gamelan.plugin.*;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

public class MyPlugin implements ExecutionInterceptorPlugin {
    private PluginContext context;
    
    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        context.getLogger().info("Plugin initialized");
    }
    
    @Override
    public void start() throws PluginException {
        context.getLogger().info("Plugin started");
    }
    
    @Override
    public void stop() throws PluginException {
        context.getLogger().info("Plugin stopped");
    }
    
    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadata(
            "my-plugin",
            "My Plugin",
            "1.0.0",
            "Your Name",
            "Description of your plugin",
            List.of(),
            Map.of()
        );
    }
}
```

### 3. Register Plugin with ServiceLoader

Create `src/main/resources/META-INF/services/tech.kayys.gamelan.plugin.Plugin`:

```
com.example.MyPlugin
```

### 4. Build Plugin JAR

```bash
mvn clean package
```

### 5. Deploy Plugin

Copy the JAR to the plugin directory:

```bash
cp target/my-plugin-1.0.0.jar /opt/gamelan/plugins/
```

## Plugin Types

### 1. Task Dispatcher Plugin

Custom task dispatchers for protocols beyond GRPC/Kafka/REST:

```java
public class CustomDispatcherPlugin implements TaskDispatcherPlugin {
    @Override
    public boolean supports(ExecutorInfo executor) {
        return "CUSTOM".equals(executor.communicationType());
    }
    
    @Override
    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
        // Custom dispatch logic
        return Uni.createFrom().voidItem();
    }
}
```

### 2. Execution Interceptor Plugin

Hook into task execution lifecycle:

```java
public class LoggingInterceptorPlugin implements ExecutionInterceptorPlugin {
    @Override
    public Uni<Void> beforeExecution(TaskContext task) {
        logger.info("Before: {}", task.nodeId());
        return Uni.createFrom().voidItem();
    }
    
    @Override
    public Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
        logger.info("After: {} - Success: {}", task.nodeId(), result.isSuccess());
        return Uni.createFrom().voidItem();
    }
}
```

### 3. Workflow Validator Plugin

Add custom validation rules:

```java
public class CustomValidatorPlugin implements WorkflowValidatorPlugin {
    @Override
    public List<ValidationError> validate(WorkflowDefinition definition) {
        List<ValidationError> errors = new ArrayList<>();
        // Validation logic
        return errors;
    }
}
```

### 4. Data Transformer Plugin

Transform input/output data:

```java
public class DataTransformerPlugin implements DataTransformerPlugin {
    @Override
    public boolean supports(String nodeType) {
        return "custom-node".equals(nodeType);
    }
    
    @Override
    public Map<String, Object> transformInput(Map<String, Object> input, NodeContext node) {
        // Transform input
        return input;
    }
}
```

### 5. Event Listener Plugin

React to workflow events:

```java
public class EventListenerPlugin implements EventListenerPlugin {
    @Override
    public void onWorkflowStarted(WorkflowStartedEvent event) {
        logger.info("Workflow started: {}", event.runId());
    }
}
```

## Plugin Context

Access engine services through PluginContext:

```java
// Get configuration
String value = context.getProperty("my-key", "default");

// Get logger
context.getLogger().info("Message");

// Register service
context.getServiceRegistry().registerService(MyService.class, new MyServiceImpl());

// Publish event
context.getEventBus().publish(new MyPluginEvent());

// Get data directory
String dataDir = context.getDataDirectory();
```

## Example Plugin

See [gamelan-plugin-example](file:///Users/bhangun/Workspace/workkayys/Products/Wayang/wayang-platform/wayang-workflow/examples/gamelan-plugin-example) for a complete working example.

## Configuration

Configure plugins in `application.yaml`:

```yaml
gamelan:
  plugins:
    enabled: true
    directory: /opt/gamelan/plugins
    hot-reload: true
    plugins:
      - id: my-plugin
        enabled: true
        config:
          custom-property: value
```

## Hot Reload

Plugins support hot-reload for development:

```java
// Reload a plugin
pluginManager.reloadPlugin("my-plugin", Paths.get("/path/to/plugin.jar"));
```

## Best Practices

1. **Keep plugins lightweight** - Minimize dependencies
2. **Use provided scope** - Plugin API should be provided by the engine
3. **Handle errors gracefully** - Don't crash the engine
4. **Log appropriately** - Use the provided logger
5. **Clean up resources** - Implement stop() properly
6. **Version your plugins** - Use semantic versioning

## Troubleshooting

### Plugin not loading

- Check ServiceLoader configuration in `META-INF/services`
- Verify plugin JAR is in the correct directory
- Check logs for initialization errors

### ClassNotFoundException

- Ensure all dependencies are packaged in the plugin JAR
- Use maven-shade-plugin to create an uber-jar if needed

### Plugin conflicts

- Check plugin dependencies
- Use different classloaders for isolation
- Verify plugin IDs are unique
