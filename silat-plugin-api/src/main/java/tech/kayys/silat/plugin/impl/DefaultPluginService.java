package tech.kayys.silat.plugin.impl;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.plugin.Plugin;
import tech.kayys.silat.plugin.PluginService;

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
    public Uni<Plugin> loadPlugin(Path pluginJar) {
        return pluginManager.loadPlugin(pluginJar);
    }

    @Override
    public Uni<Void> registerPlugin(Plugin plugin) {
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
    public Uni<Plugin> reloadPlugin(String pluginId, Path pluginJar) {
        return pluginManager.reloadPlugin(pluginId, pluginJar);
    }

    @Override
    public Optional<Plugin> getPlugin(String pluginId) {
        return pluginManager.getPlugin(pluginId);
    }

    @Override
    public List<Plugin> getAllPlugins() {
        return pluginManager.getAllPlugins();
    }

    @Override
    public <T extends Plugin> List<T> getPluginsByType(Class<T> pluginType) {
        return pluginManager.getPluginsByType(pluginType);
    }

    @Override
    public Uni<List<Plugin>> discoverAndLoadPlugins() {
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
    public void publish(tech.kayys.silat.plugin.PluginEvent event) {
        eventBus.publish(event);
    }

    @Override
    public <T extends tech.kayys.silat.plugin.PluginEvent> tech.kayys.silat.plugin.EventBus.Subscription subscribe(
            Class<T> eventType, java.util.function.Consumer<T> handler) {
        return eventBus.subscribe(eventType, handler);
    }
}