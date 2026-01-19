package tech.kayys.gamelan.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginMetadataBuilder;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

public class LoggingPlugin implements ExecutionInterceptorPlugin {
    
    private static final Logger LOG = LoggerFactory.getLogger(LoggingPlugin.class);
    
    @Override
    public void initialize(PluginContext context) {
        LOG.info("LoggingPlugin initialized: {}", context.pluginId());
    }

    @Override
    public void start() {
        LOG.info("LoggingPlugin started");
    }

    @Override
    public void stop() {
        LOG.info("LoggingPlugin stopped");
    }

    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadataBuilder()
            .id("logging-interceptor")
            .name("Logging Interceptor Plugin")
            .version("1.0.0")
            .description("Example plugin that logs execution events")
            .build();
    }

    @Override
    public Uni<Void> beforeExecution(TaskContext task) {
        LOG.info(">>> BEFORE execution of node: {}", task.nodeId());
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
        LOG.info("<<< AFTER execution of node: {} (Success: {})", task.nodeId(), result.isSuccess());
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> onError(TaskContext task, Throwable error) {
        LOG.error("!!! ERROR executing node: {}", task.nodeId(), error);
        return Uni.createFrom().voidItem();
    }
}
