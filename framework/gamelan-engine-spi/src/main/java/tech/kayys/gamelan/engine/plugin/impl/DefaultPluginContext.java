package tech.kayys.gamelan.engine.plugin.impl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginRuntimeInfo;
import tech.kayys.gamelan.engine.plugin.SemVer;
import tech.kayys.gamelan.engine.plugin.ServiceRegistry;

/**
 * Default implementation of PluginContext
 */
public class DefaultPluginContext implements PluginContext {

    private static final Pattern SEMVER_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*$");
    private static final String DEFAULT_RUNTIME_MODE = "standalone";
    private static final SemVer DEFAULT_ENGINE_VERSION = new SemVer(0, 0, 0);

    private final PluginMetadata metadata;
    private final Logger logger;
    private final Map<String, String> properties;
    private final ServiceRegistry serviceRegistry;
    private final EventBus eventBus;
    private final EventPublisher eventPublisher;
    private final ExtensionRegistry extensionRegistry;
    private final Configuration configuration;
    private String dataDirectory;

    public DefaultPluginContext(
            PluginMetadata metadata,
            Logger logger,
            Map<String, String> properties,
            ServiceRegistry serviceRegistry,
            EventBus eventBus,
            EventPublisher eventPublisher,
            ExtensionRegistry extensionRegistry,
            String dataDirectory) {
        this.metadata = metadata;
        this.logger = logger;
        this.properties = new ConcurrentHashMap<>(properties);
        this.serviceRegistry = serviceRegistry;
        this.eventBus = eventBus;
        this.eventPublisher = eventPublisher;
        this.extensionRegistry = extensionRegistry;
        this.dataDirectory = dataDirectory;
        // Create configuration from properties
        // We need a simple configuration implementation wrapping the map
        this.configuration = new Configuration() {
            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(properties.get(key));
            }

            @Override
            public <T> Optional<T> get(String key, Class<T> type) {
                // Simple string conversion for now
                return get(key).map(v -> (T) v); // This is unsafe but compilation wise ok if type is String
            }

            @Override
            public String require(String key) {
                return get(key).orElseThrow();
            }

            @Override
            public Configuration scoped(String prefix) {
                return this;
            } // Simplified
        };
    }

    @Override
    public PluginMetadata getMetadata() {
        return metadata;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public Optional<String> getProperty(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    @Override
    public Map<String, String> getAllProperties() {
        return Map.copyOf(properties);
    }

    @Override
    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public String getDataDirectory() {
        return dataDirectory;
    }

    @Override
    public PluginRuntimeInfo runtimeInfo() {
        String runtimeMode = getProperty("gamelan.runtime.mode")
                .or(() -> getProperty("gamelan.deployment-mode").map(DefaultPluginContext::mapDeploymentMode))
                .orElse(DEFAULT_RUNTIME_MODE);
        SemVer engineVersion = getProperty("gamelan.engine.version")
                .flatMap(DefaultPluginContext::parseSemVer)
                .orElse(DEFAULT_ENGINE_VERSION);
        return new PluginRuntimeInfo(runtimeMode, engineVersion);
    }

    @Override
    public Configuration config() {
        return configuration;
    }

    @Override
    public EventPublisher eventPublisher() {
        return eventPublisher;
    }

    @Override
    public ExtensionRegistry extensions() {
        return extensionRegistry;
    }

    private static String mapDeploymentMode(String deploymentMode) {
        return switch (deploymentMode.trim().toUpperCase()) {
            case "SERVER", "ENTERPRISE" -> "distributed";
            case "EXECUTOR" -> "executor";
            default -> "standalone";
        };
    }

    private static Optional<SemVer> parseSemVer(String value) {
        Matcher matcher = SEMVER_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new SemVer(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }
}
