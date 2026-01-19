package tech.kayys.gamelan.plugin.discovery;

import java.util.Optional;

import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Service Discovery Plugin Interface
 * 
 * Plugins implementing this interface provide dynamic endpoint discovery
 * capabilities for executors.
 * This effectively allows overriding the static endpoint information stored in
 * the registry.
 */
public interface ServiceDiscoveryPlugin extends GamelanPlugin {

    /**
     * Discover the endpoint for a given executor ID.
     * 
     * @param executorId The ID of the executor to discover.
     * @return An Optional containing the discovered endpoint (e.g., "host:port" or
     *         "http://host:port"),
     *         or empty if the plugin cannot find an endpoint for this executor.
     */
    Optional<String> discoverEndpoint(String executorId);

}
