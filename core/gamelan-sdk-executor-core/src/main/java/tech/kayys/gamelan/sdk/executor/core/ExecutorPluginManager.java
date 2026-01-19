package tech.kayys.gamelan.sdk.executor.core;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.plugin.executor.ExecutorPlugin;
import tech.kayys.gamelan.engine.plugin.PluginManager;

/**
 * Manager for executor plugins
 * 
 * Discovers and manages executor plugins, providing plugin selection
 * based on task requirements.
 */
@ApplicationScoped
public class ExecutorPluginManager {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorPluginManager.class);

    @Inject
    PluginManager pluginManager;

    private List<ExecutorPlugin> executorPlugins;

    @PostConstruct
    void init() {
        // Discover and load executor plugins
        executorPlugins = pluginManager.getPluginsByType(ExecutorPlugin.class).stream()
                .sorted(Comparator.comparingInt(ExecutorPlugin::getPriority).reversed())
                .collect(Collectors.toList());

        LOG.info("Loaded {} executor plugins", executorPlugins.size());
        executorPlugins.forEach(p -> LOG.info("  - {} (type: {}, priority: {})",
                p.getMetadata().name(),
                p.getExecutorType(),
                p.getPriority()));
    }

    /**
     * Find a suitable plugin for the given task
     * 
     * @param task the task to find a plugin for
     * @return the first plugin that can handle the task, or null if none found
     */
    public ExecutorPlugin findPlugin(NodeExecutionTask task) {
        return executorPlugins.stream()
                .filter(p -> p.canHandle(task))
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if any plugin can handle the given task
     * 
     * @param task the task to check
     * @return true if at least one plugin can handle the task
     */
    public boolean hasPluginFor(NodeExecutionTask task) {
        return findPlugin(task) != null;
    }

    /**
     * Get all loaded executor plugins
     * 
     * @return list of executor plugins
     */
    public List<ExecutorPlugin> getPlugins() {
        return executorPlugins;
    }
}
