package tech.kayys.gamelan.plugin.impl;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.plugin.PluginEvent;
import tech.kayys.gamelan.engine.plugin.PluginManager;
import tech.kayys.gamelan.engine.plugin.PluginService;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Unified implementation of PluginService that combines all plugin
 * functionality
 */
@ApplicationScoped
@jakarta.enterprise.inject.Typed(PluginService.class)
public class DefaultPluginService implements PluginService {

    @Inject
    PluginManager pluginManager;

    @Inject
    DefaultServiceRegistry serviceRegistry;

    @Inject
    DefaultEventBus eventBus;

    @Override
    public Uni<GamelanPlugin> loadPlugin(Path pluginJar) {
        return pluginManager.loadPlugin(pluginJar);
    }

    @Override
    public Uni<Void> registerPlugin(GamelanPlugin plugin) {
        return pluginManager.registerPlugin(plugin);
    }

    @Override
    public Uni<Void> startPlugin(String pluginId) {
        return pluginManager.startPlugin(pluginId);
    }

    @Override
    public Uni<Void> stopPlugin(String pluginId) {
        return pluginManager.stopPlugin(pluginId);
    }

    @Override
    public Uni<Void> unloadPlugin(String pluginId) {
        return pluginManager.unloadPlugin(pluginId);
    }

    @Override
    public Uni<GamelanPlugin> reloadPlugin(String pluginId, Path pluginJar) {
        return pluginManager.reloadPlugin(pluginId, pluginJar);
    }

    @Override
    public Optional<GamelanPlugin> getPlugin(String pluginId) {
        return pluginManager.getPlugin(pluginId);
    }

    @Override
    public List<GamelanPlugin> getAllPlugins() {
        return pluginManager.getAllPlugins();
    }

    @Override
    public <T extends GamelanPlugin> List<T> getPluginsByType(Class<T> pluginType) {
        return pluginManager.getPluginsByType(pluginType);
    }

    @Override
    public Uni<List<GamelanPlugin>> discoverAndLoadPlugins() {
        return pluginManager.discoverAndLoadPlugins();
    }

    @Override
    public void setPluginDirectory(String pluginDirectory) {
        pluginManager.setPluginDirectory(pluginDirectory);
    }

    @Override
    public void setDataDirectory(String dataDirectory) {
        pluginManager.setDataDirectory(dataDirectory);
    }

    @Override
    public <T> void registerService(Class<T> serviceType, T service) {
        serviceRegistry.registerService(serviceType, service);
    }

    @Override
    public <T> void unregisterService(Class<T> serviceType) {
        serviceRegistry.unregisterService(serviceType);
    }

    @Override
    public <T> Optional<T> getService(Class<T> serviceType) {
        return serviceRegistry.getService(serviceType);
    }

    @Override
    public boolean hasService(Class<?> serviceType) {
        return serviceRegistry.hasService(serviceType);
    }

    @Override
    public void publish(PluginEvent event) {
        eventBus.publish(event);
    }

    @Override
    public <T extends PluginEvent> EventBus.Subscription subscribe(
            Class<T> eventType, Consumer<T> handler) {
        return eventBus.subscribe(eventType, handler);
    }
}