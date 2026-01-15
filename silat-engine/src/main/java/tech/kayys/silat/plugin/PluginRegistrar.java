package tech.kayys.silat.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import tech.kayys.silat.plugin.defaultplugins.DefaultPluginBundle;

/**
 * Initializes and registers default plugins when the application starts
 */
@ApplicationScoped
public class PluginRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(PluginRegistrar.class);

    @Inject
    PluginService pluginService;

    public void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing default plugins...");

        // Set plugin directory
        pluginService.setPluginDirectory("./plugins");
        pluginService.setDataDirectory("./plugin-data");

        try {
            // Create and register the default plugin bundle programmatically
            DefaultPluginBundle defaultBundle = new DefaultPluginBundle();

            // Register the plugin bundle with the service
            pluginService.registerPlugin(defaultBundle)
                    .subscribe().with(
                            result -> {
                                LOG.info("Default plugin bundle registered: {}", defaultBundle.getMetadata().name());

                                // Start the default plugin bundle
                                pluginService.startPlugin(defaultBundle.getMetadata().id())
                                        .subscribe().with(
                                                startResult -> LOG.info("Default plugin bundle started successfully"),
                                                error -> LOG.error("Failed to start default plugin bundle", error));
                            },
                            error -> LOG.error("Failed to register default plugin bundle", error));

            // Auto-register Consul Service Discovery Plugin
            tech.kayys.silat.plugin.consul.ConsulServiceDiscoveryPlugin consulPlugin = new tech.kayys.silat.plugin.consul.ConsulServiceDiscoveryPlugin();
            pluginService.registerPlugin(consulPlugin)
                    .subscribe().with(
                            result -> {
                                LOG.info("Consul plugin registered: {}", consulPlugin.getMetadata().name());
                                pluginService.startPlugin(consulPlugin.getMetadata().id())
                                        .subscribe().with(
                                                startResult -> LOG.info("Consul plugin started successfully"),
                                                error -> LOG.error("Failed to start Consul plugin", error));
                            },
                            error -> LOG.error("Failed to register Consul plugin", error));

        } catch (Exception e) {
            LOG.error("Failed to initialize default plugins", e);
        }

        LOG.info("Default plugin initialization completed. Total plugins loaded: {}",
                pluginService.getAllPlugins().size());
    }
}