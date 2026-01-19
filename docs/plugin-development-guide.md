Gamelan Plugin Development Guide
This guide explains how to create custom plugins for the Gamelan workflow engine.

Overview
Gamelan plugins extend the workflow engine's capabilities through a well-defined plugin API. Plugins can intercept execution, add custom logic, integrate external systems, and more.

Plugin Types
1. ExecutionInterceptorPlugin
Intercepts node execution to add cross-cutting concerns like logging, metrics, security, etc.

Interface:

public interface ExecutionInterceptorPlugin extends GamelanPlugin {
    int getOrder(); // Execution order (lower = earlier)
    Uni<Void> beforeExecution(TaskContext task);
    Uni<Void> afterExecution(TaskContext task, ExecutionResult result);
    Uni<Void> onError(TaskContext task, Throwable error);
}
Example: See examples/gamelan-plugin-example/LoggingPlugin.java

2. Future Plugin Types (Coming Soon)
WorkflowInterceptorPlugin: Intercept workflow lifecycle events
StateTransitionPlugin: Hook into workflow state changes
ValidationPlugin: Validate workflow definitions and inputs
Creating a Plugin
Step 1: Set Up Project
Create a new Maven project:

<project>
    <groupId>com.example</groupId>
    <artifactId>my-gamelan-plugin</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    
    <dependencies>
        <dependency>
            <groupId>tech.kayys.gamelan</groupId>
            <artifactId>gamelan-plugin-api</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
Step 2: Implement Plugin Interface
package com.example;
import tech.kayys.gamelan.engine.plugin.*;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;
import io.smallrye.mutiny.Uni;
public class MyPlugin implements ExecutionInterceptorPlugin {
    
    @Override
    public void initialize(PluginContext context) {
        // Initialize your plugin
        // Access EngineContext via context.engineContext()
    }
    
    @Override
    public void start() {
        // Start plugin services
    }
    
    @Override
    public void stop() {
        // Cleanup resources
    }
    
    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadataBuilder()
            .id("my-plugin")
            .name("My Custom Plugin")
            .version("1.0.0")
            .description("Does something awesome")
            .build();
    }
    
    @Override
    public int getOrder() {
        return 100; // Execution order
    }
    
    @Override
    public Uni<Void> beforeExecution(TaskContext task) {
        // Logic before node execution
        return Uni.createFrom().voidItem();
    }
    
    @Override
    public Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
        // Logic after node execution
        return Uni.createFrom().voidItem();
    }
    
    @Override
    public Uni<Void> onError(TaskContext task, Throwable error) {
        // Error handling logic
        return Uni.createFrom().voidItem();
    }
}
Step 3: Register with ServiceLoader
Create 
src/main/resources/META-INF/services/tech.kayys.gamelan.engine.plugin.GamelanPlugin
:

com.example.MyPlugin
Step 4: Build Plugin JAR
mvn clean package
Step 5: Deploy Plugin
Copy the JAR to the plugins directory:

cp target/my-gamelan-plugin-1.0.0.jar /path/to/gamelan/plugins/
Plugin Context
Plugins receive a 
PluginContext
 during initialization:

public interface PluginContext {
    EngineContext engineContext();
    String pluginId();
}
Access engine services:

EngineContext engine = context.engineContext();
PluginRegistry registry = engine.pluginRegistry();
EventBus eventBus = engine.eventBus();
ExecutorDispatcher dispatcher = engine.executorDispatcher();
Best Practices
Keep plugins stateless - Store state in workflow context, not plugin instances
Handle errors gracefully - Return Uni.createFrom().voidItem() on non-critical errors
Use proper ordering - Lower order numbers execute first
Log appropriately - Use SLF4J for logging
Test thoroughly - Write unit tests for your plugin logic
Testing Plugins
@Test
public void testMyPlugin() {
    MyPlugin plugin = new MyPlugin();
    PluginContext context = mock(PluginContext.class);
    
    plugin.initialize(context);
    plugin.start();
    
    TaskContext task = mock(TaskContext.class);
    when(task.nodeId()).thenReturn("test-node");
    
    Uni<Void> result = plugin.beforeExecution(task);
    
    result.subscribe().withSubscriber(UniAssertSubscriber.create())
        .awaitItem(Duration.ofSeconds(1));
    
    plugin.stop();
}
Examples
See examples/gamelan-plugin-example for a complete working example.

Troubleshooting
Plugin not loading:

Check JAR is in plugins directory
Verify META-INF/services file exists and is correct
Check logs for "Loaded plugin: ..." message
Plugin errors:

Check plugin logs for exceptions
Verify all dependencies are provided
Ensure plugin metadata is valid
Advanced Topics
Accessing Workflow State
@Override
public Uni<Void> beforeExecution(TaskContext task) {
    String runId = task.runId();
    String nodeId = task.nodeId();
    Map<String, Object> inputs = task.inputs();
    
    // Your logic here
    return Uni.createFrom().voidItem();
}
Publishing Events
EngineContext engine = context.engineContext();
EventBus eventBus = engine.eventBus();
eventBus.publish("custom.event", payload);
Plugin Dependencies
Declare dependencies in metadata:

new PluginMetadataBuilder()
    .id("my-plugin")
    .dependencies(List.of("other-plugin-id"))
    .build();
Support
For questions and issues, please refer to the Gamelan documentation or open an issue on GitHub.