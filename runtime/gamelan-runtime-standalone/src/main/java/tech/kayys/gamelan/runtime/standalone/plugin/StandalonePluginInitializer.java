package tech.kayys.gamelan.runtime.standalone.plugin;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.plugin.PluginManager;

@ApplicationScoped
public class StandalonePluginInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(StandalonePluginInitializer.class);

    @Inject
    PluginManager pluginManager;

    @Inject
    @ConfigProperty(name = "gamelan.plugins.directory", defaultValue = "~/./gamelan/plugins")
    String pluginsDirectory;

    @Inject
    @ConfigProperty(name = "gamelan.plugins.auto-discover", defaultValue = "true")
    boolean autoDiscoverPlugins;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing Standalone Plugin Manager...");

        // Configure the core plugin manager
        pluginManager.setPluginDirectory(pluginsDirectory);
        // We could also set data directory if needed, defaulting for now

        if (autoDiscoverPlugins) {
            LOG.info("Auto-discovering plugins from: {}", pluginsDirectory);
            pluginManager.discoverAndLoadPlugins()
                    .subscribe().with(
                            plugins -> LOG.info("Successfully loaded {} plugins", plugins.size()),
                            failure -> LOG.error("Failed to load plugins during startup", failure));
        }
    }
}
