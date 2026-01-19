package tech.kayys.gamelan.core.engine;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.core.config.PluginConfig;
import tech.kayys.gamelan.core.plugin.FilePluginLoader;
import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.SecurityContext;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.executor.ExecutorClientFactory;
import tech.kayys.gamelan.engine.executor.ExecutorDispatcher;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.plugin.PluginRegistry;
import tech.kayys.gamelan.engine.plugin.PluginRegistry.LoadedPlugin;

@ApplicationScoped
public class DefaultEngineContext implements EngineContext {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultEngineContext.class);

    @Inject
    PluginRegistry pluginRegistry;
    @Inject
    ExecutorDispatcher executorDispatcher;
    @Inject
    EventBus eventBus;
    @Inject
    PersistenceProvider persistence;
    @Inject
    SecurityContext security;
    @Inject
    EventPublisher eventPublisher;
    @Inject
    Configuration configuration;
    @Inject
    ExecutorClientFactory clientFactory;
    @Inject
    PluginConfig pluginConfig;

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        LOG.info("Initializing DefaultEngineContext");
    }

    void onStart(@Observes Startup event) {
        LOG.info("Gamelan Engine starting up...");
        loadPlugins();
    }

    private void loadPlugins() {
        // Check if plugin loading is enabled
        if (!pluginConfig.enabled().orElse(true)) {
            LOG.info("Plugin loading is disabled");
            return;
        }

        LOG.info("Loading plugins from directories: {}",
                pluginConfig.directories().orElse(List.of("plugins")));

        FilePluginLoader loader = new FilePluginLoader(pluginConfig);
        List<LoadedPlugin> plugins = loader.loadPlugins();

        for (LoadedPlugin plugin : plugins) {
            try {
                pluginRegistry.register(plugin);
                // Initialize plugin
                plugin.getPlugin().initialize(
                        new tech.kayys.gamelan.core.plugin.PluginContextImpl(this, plugin.getMetadata().id()));
                plugin.getPlugin().start();
                plugin.setState(PluginRegistry.PluginState.STARTED);
            } catch (Exception e) {
                LOG.error("Failed to start plugin {}", plugin.getMetadata().name(), e);
                plugin.setState(PluginRegistry.PluginState.FAILED);
            }
        }
    }

    @Override
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public PluginRegistry pluginRegistry() {
        return pluginRegistry;
    }

    @Override
    public ExecutorDispatcher executorDispatcher() {
        return executorDispatcher;
    }

    @Override
    public EventBus eventBus() {
        return eventBus;
    }

    @Override
    public PersistenceProvider persistence() {
        return persistence;
    }

    @Override
    public SecurityContext security() {
        return security;
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public <T> T getService(Class<T> type) {
        return CDI.current().select(type).get();
    }

    @Override
    public EventPublisher eventPublisher() {
        return eventPublisher;
    }

    @Override
    public Configuration configuration() {
        return configuration;
    }

    @Override
    public ExecutorClientFactory executorClientFactory() {
        return clientFactory;
    }
}
