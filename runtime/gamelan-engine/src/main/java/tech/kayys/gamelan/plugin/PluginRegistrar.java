package tech.kayys.gamelan.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.plugin.PluginService;
import io.quarkus.runtime.StartupEvent;

/**
 * Initializes and registers default plugins when the application starts
 */
@ApplicationScoped
public class PluginRegistrar {

        private static final Logger LOG = LoggerFactory.getLogger(PluginRegistrar.class);

        @Inject
        PluginService pluginService;

        public void onStart(@Observes StartupEvent ev) {
                LOG.info("Initializing plugins...");

                // Set plugin directory
                pluginService.setPluginDirectory("./plugins");
                pluginService.setDataDirectory("./plugin-data");

                // 1. Discover and load all plugins (from classpath and directory)
                pluginService.discoverAndLoadPlugins()
                                .subscribe().with(
                                                plugins -> LOG.info("Successfully discovered and loaded {} plugins",
                                                                plugins.size()),
                                                error -> LOG.error("Failed to discover and load plugins", error));

                try {
                        // 2. Register programmatic plugins if needed
                        // (bundle registration logic remains if needed for specific non-ServiceLoader
                        // plugins)
                } catch (Exception e) {
                        LOG.error("Failed to initialize additional plugins", e);
                }

                LOG.info("Plugin initialization completed.");
        }
}