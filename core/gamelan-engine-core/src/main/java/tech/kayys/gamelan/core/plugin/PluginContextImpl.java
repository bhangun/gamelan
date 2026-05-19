package tech.kayys.gamelan.core.plugin;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginRuntimeInfo;
import tech.kayys.gamelan.engine.plugin.SemVer;
import tech.kayys.gamelan.engine.plugin.ServiceRegistry;

public class PluginContextImpl implements PluginContext {

    private static final Pattern SEMVER_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*$");
    private static final SemVer DEFAULT_ENGINE_VERSION = new SemVer(0, 0, 0);

    private final EngineContext engineContext;
    private final String pluginId;
    private final Logger logger;

    public PluginContextImpl(EngineContext engineContext, String pluginId) {
        this.engineContext = engineContext;
        this.pluginId = pluginId;
        this.logger = LoggerFactory.getLogger("plugin." + pluginId);
    }

    @Override
    public PluginMetadata getMetadata() {
        return engineContext.pluginRegistry().getPlugin(pluginId)
                .map(p -> p.getMetadata())
                .orElse(null);
    }

    @Override
    public Logger getLogger() {
        return logger;
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
        // This is a bit limited for now
        return Map.of();
    }

    @Override
    public ServiceRegistry getServiceRegistry() {
        // Return a mock or null if not yet implemented
        return null;
    }

    @Override
    public EventBus getEventBus() {
        return engineContext.eventBus();
    }

    @Override
    public String getDataDirectory() {
        return "data/plugins/" + pluginId;
    }

    @Override
    public PluginRuntimeInfo runtimeInfo() {
        String runtimeMode = config().get("gamelan.runtime.mode")
                .or(() -> config().get("gamelan.deployment-mode").map(PluginContextImpl::mapDeploymentMode))
                .orElse("standalone");
        SemVer engineVersion = config().get("gamelan.engine.version")
                .flatMap(PluginContextImpl::parseSemVer)
                .or(() -> Optional.ofNullable(PluginContextImpl.class.getPackage().getImplementationVersion())
                        .flatMap(PluginContextImpl::parseSemVer))
                .orElse(DEFAULT_ENGINE_VERSION);
        return new PluginRuntimeInfo(runtimeMode, engineVersion);
    }

    @Override
    public Configuration config() {
        return engineContext.configuration().scoped("plugin." + pluginId);
    }

    @Override
    public EventPublisher eventPublisher() {
        return engineContext.eventPublisher();
    }

    @Override
    public ExtensionRegistry extensions() {
        return engineContext.getService(ExtensionRegistry.class);
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
