package tech.kayys.gamelan.example;

import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.plugin.PluginContext;
import tech.kayys.gamelan.plugin.PluginException;
import tech.kayys.gamelan.plugin.PluginMetadata;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

/**
 * Example execution interceptor plugin that logs all task executions
 */
public class LoggingInterceptorPlugin implements ExecutionInterceptorPlugin {
    
    private PluginContext context;
    
    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        context.getLogger().info("Logging Interceptor Plugin initialized");
    }
    
    @Override
    public void start() throws PluginException {
        context.getLogger().info("Logging Interceptor Plugin started");
    }
    
    @Override
    public void stop() throws PluginException {
        context.getLogger().info("Logging Interceptor Plugin stopped");
    }
    
    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadata(
            "logging-interceptor",
            "Logging Interceptor Plugin",
            "1.0.0",
            "Gamelan Team",
            "Logs all task executions for debugging and monitoring",
            List.of(),
            Map.of("log-level", "INFO")
        );
    }
    
    @Override
    public Uni<Void> beforeExecution(TaskContext task) {
        context.getLogger().info(
            "BEFORE EXECUTION: runId={}, nodeId={}, nodeType={}, attempt={}",
            task.runId(),
            task.nodeId(),
            task.nodeType(),
            task.attempt()
        );
        return Uni.createFrom().voidItem();
    }
    
    @Override
    public Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
        context.getLogger().info(
            "AFTER EXECUTION: runId={}, nodeId={}, success={}, outputs={}",
            task.runId(),
            task.nodeId(),
            result.isSuccess(),
            result.outputs().size()
        );
        return Uni.createFrom().voidItem();
    }
    
    @Override
    public Uni<Void> onError(TaskContext task, Throwable error) {
        context.getLogger().error(
            "EXECUTION ERROR: runId={}, nodeId={}, error={}",
            task.runId(),
            task.nodeId(),
            error.getMessage(),
            error
        );
        return Uni.createFrom().voidItem();
    }
    
    @Override
    public int getOrder() {
        return 100; // Execute after most other interceptors
    }
}
