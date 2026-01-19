package tech.kayys.gamelan.plugin.defaultplugins;

import java.util.Map;

import org.slf4j.Logger;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginException;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

/**
 * Default logging interceptor plugin that logs workflow execution events
 */
public class LoggingInterceptorPlugin implements ExecutionInterceptorPlugin {

    private PluginContext context;
    private Logger logger;
    private volatile boolean started = false;

    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        this.logger = context.getLogger();
        logger.info("Logging Interceptor Plugin initialized");
    }

    public void start() throws PluginException {
        started = true;
        logger.info("Logging Interceptor Plugin started");
    }

    public void stop() throws PluginException {
        started = false;
        logger.info("Logging Interceptor Plugin stopped");
    }

    public PluginMetadata getMetadata() {
        return new PluginMetadata(
                "logging-interceptor",
                "Logging Interceptor Plugin",
                "1.0.0",
                "Gamelan Team",
                "Logs workflow execution events",
                null,
                null);
    }

    @Override
    public Uni<Void> beforeExecution(TaskContext task) {
        if (started) {
            logger.info("Starting execution of node '{}' in workflow '{}'",
                task.nodeId(), task.runId());
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
        if (started) {
            logger.info("Completed execution of node '{}' in workflow '{}', success: {}",
                task.nodeId(), task.runId(), result.isSuccess());
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> onError(TaskContext task, Throwable error) {
        if (started) {
            logger.error("Execution of node '{}' in workflow '{}' encountered error: {}",
                task.nodeId(), task.runId(), error.getMessage(), error);
        }
        return Uni.createFrom().voidItem();
    }
}