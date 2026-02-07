package tech.kayys.gamelan.engine.plugin;

import org.slf4j.Logger;

import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;

import java.util.Map;
import java.util.Optional;

/**
 * Plugin context providing access to engine services and configuration
 */
public interface PluginContext {

    /**
     * Get the plugin metadata
     */
    PluginMetadata getMetadata();

    /**
     * Get a logger instance for this plugin
     */
    Logger getLogger();

    /**
     * Get a configuration property
     * 
     * @param key the property key
     * @return the property value if present
     */
    Optional<String> getProperty(String key);

    /**
     * Get a configuration property with a default value
     * 
     * @param key          the property key
     * @param defaultValue the default value if property is not found
     * @return the property value or default
     */
    String getProperty(String key, String defaultValue);

    /**
     * Get all configuration properties
     */
    Map<String, String> getAllProperties();

    /**
     * Get the service registry for inter-plugin communication
     */
    ServiceRegistry getServiceRegistry();

    /**
     * Get the event bus for publishing/subscribing to events
     */
    EventBus getEventBus();

    /**
     * Get the plugin data directory for storing plugin-specific data
     */
    String getDataDirectory();

    PluginRuntimeInfo runtimeInfo();

    Configuration config();

    EventPublisher eventPublisher();

    ExtensionRegistry extensions();
}
