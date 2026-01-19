package tech.kayys.gamelan.plugin.defaultplugins;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import tech.kayys.gamelan.engine.plugin.GamelanPlugin;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginException;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginMetadataBuilder;

/**
 * A bundle that contains multiple default plugins
 * This allows loading multiple plugins as a single unit
 */
public class DefaultPluginBundle implements GamelanPlugin {

    private Logger logger;

    private final List<GamelanPlugin> bundledPlugins = Arrays.asList(
            new LoggingInterceptorPlugin(),
            new MetricsCollectorPlugin(),
            new SimpleValidatorPlugin());

    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.logger = context.getLogger();

        logger.info("Initializing Default Plugin Bundle with {} plugins", bundledPlugins.size());

        for (GamelanPlugin plugin : bundledPlugins) {
            try {
                plugin.initialize(context);
                logger.debug("Initialized plugin: {}", plugin.getMetadata().name());
            } catch (Exception e) {
                throw new PluginException("Failed to initialize plugin: " + plugin.getMetadata().name(), e);
            }
        }

        logger.info("Default Plugin Bundle initialized successfully");
    }

    @Override
    public void start() throws PluginException {
        logger.info("Starting Default Plugin Bundle...");

        for (GamelanPlugin plugin : bundledPlugins) {
            try {
                plugin.start();
                logger.debug("Started plugin: {}", plugin.getMetadata().name());
            } catch (Exception e) {
                throw new PluginException("Failed to start plugin: " + plugin.getMetadata().name(), e);
            }
        }

        logger.info("Default Plugin Bundle started successfully");
    }

    @Override
    public void stop() throws PluginException {
        logger.info("Stopping Default Plugin Bundle...");

        for (GamelanPlugin plugin : bundledPlugins) {
            try {
                plugin.stop();
                logger.debug("Stopped plugin: {}", plugin.getMetadata().name());
            } catch (Exception e) {
                logger.error("Failed to stop plugin: {}", plugin.getMetadata().name(), e);
            }
        }

        logger.info("Default Plugin Bundle stopped successfully");
    }

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadataBuilder.builder()
                .id("default-plugin-bundle")
                .name("Default Plugin Bundle")
                .version("1.0.0")
                .author("Gamelan Team")
                .description("A bundle of default plugins for the Gamelan workflow engine")
                .build();
    }

    /**
     * Get the plugins contained in this bundle
     */
    public Collection<GamelanPlugin> getBundledPlugins() {
        return Collections.unmodifiableList(bundledPlugins);
    }

    /**
     * Get a specific plugin by type from the bundle
     */
    @SuppressWarnings("unchecked")
    public <T extends GamelanPlugin> T getPluginByType(Class<T> pluginType) {
        return (T) bundledPlugins.stream()
                .filter(pluginType::isInstance)
                .findFirst()
                .orElse(null);
    }
}