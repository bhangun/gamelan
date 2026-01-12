package tech.kayys.silat.sdk.client;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import tech.kayys.silat.model.WorkflowDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * REST-based workflow definition client
 */
public class RestWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final SilatClientConfig config;
    private final Vertx vertx;
    private final WebClient webClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RestWorkflowDefinitionClient(SilatClientConfig config) {
        this.config = config;

        // Create Vertx instance and WebClient with proper configuration
        this.vertx = Vertx.vertx();
        WebClientOptions options = new WebClientOptions()
                .setDefaultHost(getHostFromEndpoint(config.endpoint()))
                .setDefaultPort(getPortFromEndpoint(config.endpoint()))
                .setSsl(config.endpoint().toLowerCase().startsWith("https"))
                .setConnectTimeout((int) config.timeout().toMillis())
                .setIdleTimeout((int) config.timeout().getSeconds());

        this.webClient = WebClient.create(vertx, options);
    }

    @Override
    public Uni<WorkflowDefinition> createDefinition(WorkflowDefinition request) {
        if (closed.get()) {
            return Uni.createFrom().failure(new IllegalStateException("Client is closed"));
        }

        JsonObject requestBody = JsonObject.mapFrom(request);

        return applyAuthHeaders(webClient
                .post(getPath("/workflow-definitions"))
                .putHeader("Content-Type", "application/json")
                .putHeader("Tenant-ID", config.tenantId()))
                .sendJson(requestBody)
                .onItem().transform(response -> response.bodyAsJson(WorkflowDefinition.class))
                .onFailure().recoverWithUni(failure -> Uni.createFrom().failure(
                        new RuntimeException("Failed to create workflow definition: " + failure.getMessage(),
                                failure)));
    }

    @Override
    public Uni<WorkflowDefinition> getDefinition(String definitionId) {
        return applyAuthHeaders(webClient
                .get(getPath("/workflow-definitions/" + definitionId))
                .putHeader("Accept", "application/json")
                .putHeader("Tenant-ID", config.tenantId()))
                .send()
                .onItem().transform(response -> response.bodyAsJson(WorkflowDefinition.class))
                .onFailure().recoverWithUni(failure -> Uni.createFrom().failure(
                        new RuntimeException("Failed to get workflow definition: " + failure.getMessage(), failure)));
    }

    @Override
    public Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly) {
        String query = activeOnly ? "?activeOnly=true" : "";
        return applyAuthHeaders(webClient
                .get(getPath("/workflow-definitions" + query))
                .putHeader("Accept", "application/json")
                .putHeader("Tenant-ID", config.tenantId()))
                .send()
                .onItem().transform(response -> {
                    JsonArray array = response.bodyAsJsonArray();
                    return array.stream()
                            .map(item -> (JsonObject) item)
                            .map(json -> json.mapTo(WorkflowDefinition.class))
                            .collect(Collectors.toList());
                })
                .onFailure().recoverWithUni(failure -> Uni.createFrom().failure(
                        new RuntimeException("Failed to list workflow definitions: " + failure.getMessage(), failure)));
    }

    @Override
    public Uni<Void> deleteDefinition(String definitionId) {
        return applyAuthHeaders(webClient
                .delete(getPath("/workflow-definitions/" + definitionId))
                .putHeader("Tenant-ID", config.tenantId()))
                .send()
                .onItem().transformToUni(response -> Uni.createFrom().voidItem())
                .onFailure().recoverWithUni(failure -> Uni.createFrom().failure(
                        new RuntimeException("Failed to delete workflow definition: " + failure.getMessage(),
                                failure)));
    }

    /**
     * Apply authentication headers based on configuration
     */
    private <T> io.vertx.mutiny.ext.web.client.HttpRequest<T> applyAuthHeaders(
            io.vertx.mutiny.ext.web.client.HttpRequest<T> request) {
        if (config.apiKey() != null && !config.apiKey().trim().isEmpty()) {
            request.putHeader("Authorization", "Bearer " + config.apiKey());
        }
        // Add any additional headers from config
        config.headers().forEach(request::putHeader);
        return request;
    }

    /**
     * Extract host from endpoint URL
     */
    private String getHostFromEndpoint(String endpoint) {
        if (endpoint.startsWith("http")) {
            return java.net.URI.create(endpoint).getHost();
        }
        // For host:port format
        int colonIndex = endpoint.indexOf(':');
        if (colonIndex != -1) {
            return endpoint.substring(0, colonIndex);
        }
        return endpoint;
    }

    /**
     * Extract port from endpoint URL
     */
    private int getPortFromEndpoint(String endpoint) {
        if (endpoint.startsWith("http")) {
            java.net.URI uri = java.net.URI.create(endpoint);
            int port = uri.getPort();
            if (port == -1) {
                return uri.getScheme().equals("https") ? 443 : 80;
            }
            return port;
        }
        // For host:port format
        int colonIndex = endpoint.indexOf(':');
        if (colonIndex != -1) {
            return Integer.parseInt(endpoint.substring(colonIndex + 1));
        }
        // Default to 80 for REST
        return 80;
    }

    /**
     * Get the API path, handling both absolute and relative endpoints
     */
    private String getPath(String path) {
        if (config.endpoint().startsWith("http")) {
            // If endpoint is a full URL, just return the path
            return path;
        } else {
            // If endpoint is host:port, prepend with "/"
            return path;
        }
    }

    /**
     * Close the client and release resources
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (webClient != null) {
                webClient.close();
            }
            if (vertx != null) {
                vertx.close();
            }
        }
    }
}
