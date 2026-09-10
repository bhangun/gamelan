package tech.kayys.gamelan.plugin.impl;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginMetadataBuilder;
import tech.kayys.gamelan.engine.plugin.PluginRuntimeInfo;
import tech.kayys.gamelan.engine.plugin.ServiceRegistry;

public class RuntimePluginContext implements PluginContext {

    private final PluginRuntimeInfo runtimeInfo;
    private final DefaultExtensionRegistry extensionRegistry;

    public RuntimePluginContext(
            PluginRuntimeInfo runtimeInfo,
            DefaultExtensionRegistry extensionRegistry) {
        this.runtimeInfo = runtimeInfo;
        this.extensionRegistry = extensionRegistry;
    }

    @Override
    public PluginRuntimeInfo runtimeInfo() {
        return runtimeInfo;
    }

    @Override
    public ExtensionRegistry extensions() {
        return extensionRegistry;
    }

    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadataBuilder()
                .id("runtime")
                .name("Gamelan Runtime")
                .version("1.0.0")
                .build();
    }

    @Override
    public Logger getLogger() {
        return LoggerFactory.getLogger(RuntimePluginContext.class);
    }

    @Override
    public Optional<String> getProperty(String key) {
        return config().get(key);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        return config().get(key).orElse(defaultValue);
    }

    @Override
    public Map<String, String> getAllProperties() {
        return Map.of(); // TODO: Expose all config
    }

    @Override
    public ServiceRegistry getServiceRegistry() {
        return jakarta.enterprise.inject.spi.CDI.current().select(ServiceRegistry.class).get();
    }

    @Override
    public EventBus getEventBus() {
        return jakarta.enterprise.inject.spi.CDI.current().select(EventBus.class).get();
    }

    @Override
    public String getDataDirectory() {
        return System.getProperty("user.dir");
    }

    @Override
    public Configuration config() {
        return jakarta.enterprise.inject.spi.CDI.current().select(Configuration.class).get();
    }

    @Override
    public EventPublisher eventPublisher() {
        return jakarta.enterprise.inject.spi.CDI.current().select(EventPublisher.class).get();
    }

    // config() / eventPublisher() omitted for clarity
}
