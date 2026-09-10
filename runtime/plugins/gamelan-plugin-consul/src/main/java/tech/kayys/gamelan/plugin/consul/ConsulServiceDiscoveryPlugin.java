package tech.kayys.gamelan.plugin.consul;

import java.util.Optional;
import java.util.Map;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginException;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.plugin.discovery.ServiceDiscoveryPlugin;

/**
 * Service Discovery Plugin implementation using Consul
 */
public class ConsulServiceDiscoveryPlugin implements ServiceDiscoveryPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulServiceDiscoveryPlugin.class);
    
    // Default Consul Configuration
    private static final String DEFAULT_CONSUL_HOST = "localhost";
    private static final int DEFAULT_CONSUL_PORT = 8500;
    
    private PluginContext context;
    private WebClient webClient;
    private Vertx vertx;
    
    private String consulHost;
    private int consulPort;

    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        Map<String, String> props = context.getAllProperties();
        
        this.consulHost = props.getOrDefault("consul.host", DEFAULT_CONSUL_HOST);
        String portStr = props.getOrDefault("consul.port", String.valueOf(DEFAULT_CONSUL_PORT));
        
        try {
            this.consulPort = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            LOG.warn("Invalid consul.port property '{}', using default {}", portStr, DEFAULT_CONSUL_PORT);
            this.consulPort = DEFAULT_CONSUL_PORT;
        }
        
        LOG.info("Consul Service Discovery Plugin initialized (Consul: {}:{})", consulHost, consulPort);
    }

    @Override
    public void start() throws PluginException {
        // Create Vert.x instance if not provided (in a real plugin system this might come from context)
        // For plugins, we might want to share the main application's Vert.x instance if possible,
        // but creating a lightweight one for the client is acceptable for now.
        this.vertx = Vertx.vertx();
        this.webClient = WebClient.create(vertx);
        LOG.info("Consul Service Discovery Plugin started");
    }

    @Override
    public void stop() throws PluginException {
        if (webClient != null) {
            webClient.close();
        }
        if (vertx != null) {
            vertx.close().await().indefinitely();
        }
        LOG.info("Consul Service Discovery Plugin stopped");
    }

    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadata(
            "consul-service-discovery",
            "Consul Service Discovery",
            "1.0.0",
            "Tech Kayys",
            "Provides executor discovery via Consul Catalog API",
            java.util.Collections.emptyList(),
            Map.of("consul.host", "localhost", "consul.port", "8500")
        );
    }

    /**
     * Discover endpoint for an executor ID from Consul
     * Queries: /v1/catalog/service/{executorId}
     */
    @Override
    public Optional<String> discoverEndpoint(String executorId) {
        if (webClient == null) {
            LOG.warn("Plugin not started, cannot discover endpoint");
            return Optional.empty();
        }

        try {
            // Note: In a reactive/Uni world, this blocking awaiting is less than ideal for high throughput,
            // but the ServiceDiscoveryPlugin interface is synchronous (Optional<String>). 
            // Ideally, the interface should return Uni<Optional<String>>.
            // For now, we block with a timeout.
            
            String serviceName = sanitizeServiceName(executorId);
            LOG.debug("Querying Consul for service: {}", serviceName);

            io.vertx.mutiny.ext.web.client.HttpResponse<io.vertx.mutiny.core.buffer.Buffer> response = 
                webClient.get(consulPort, consulHost, "/v1/catalog/service/" + serviceName)
                    .send()
                    .await().atMost(Duration.ofSeconds(2));

            if (response.statusCode() == 200) {
                JsonArray services = response.bodyAsJsonArray();
                if (services != null && !services.isEmpty()) {
                    JsonObject service = services.getJsonObject(0);
                    String address = service.getString("ServiceAddress");
                    Integer port = service.getInteger("ServicePort");
                    
                    // Fallback to Node address if ServiceAddress is empty
                    if (address == null || address.isEmpty()) {
                        address = service.getString("Address"); // Node address
                    }

                    if (address != null && port != null) {
                         // Construct endpoint string (assuming gRPC/http host:port format)
                         // If executor metadata has protocol, we might want to respect it, but for now returned host:port
                         String endpoint = address + ":" + port;
                         LOG.debug("Discovered endpoint for {}: {}", executorId, endpoint);
                         return Optional.of(endpoint);
                    }
                }
            } else {
                LOG.warn("Consul query failed with status: {}", response.statusCode());
            }

        } catch (Exception e) {
            LOG.error("Failed to query Consul for executor: {}", executorId, e);
        }
        
        return Optional.empty();
    }
    
    private String sanitizeServiceName(String executorId) {
        // Consul service names should be DNS compatible. 
        // Gamelan Executor IDs might need normalization.
        return executorId.replaceAll("[^a-zA-Z0-9-]", "-");
    }
}
