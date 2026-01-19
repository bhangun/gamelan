package tech.kayys.gamelan.sdk.executor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.plugin.executor.ExecutorPlugin;

/**
 * Plugin-based executor that delegates task execution to loaded plugins
 * 
 * This executor discovers the appropriate plugin for each task and
 * delegates execution to it.
 */
@Executor(executorType = "plugin-based")
@ApplicationScoped
public class PluginBasedExecutor extends AbstractWorkflowExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(PluginBasedExecutor.class);

    @Inject
    ExecutorPluginManager pluginManager;

    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        String taskType = extractNodeType(task);
        LOG.debug("Finding plugin for task: {} (type: {})", task.nodeId(), taskType);

        // Find suitable plugin
        ExecutorPlugin plugin = pluginManager.findPlugin(task);

        if (plugin == null) {
            LOG.error("No plugin found for task: {} (type: {})", task.nodeId(), taskType);
            return Uni.createFrom().item(SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    ErrorInfo.of(new IllegalStateException("No executor plugin found for task type: " + taskType)),
                    task.token()));
        }

        LOG.info("Executing task {} with plugin: {}", task.nodeId(), plugin.getMetadata().name());
        return plugin.execute(task);
    }

    @Override
    public boolean canHandle(NodeExecutionTask task) {
        boolean canHandle = pluginManager.hasPluginFor(task);
        LOG.debug("Can handle task {} (type: {}): {}", task.nodeId(), extractNodeType(task), canHandle);
        return canHandle;
    }
}
