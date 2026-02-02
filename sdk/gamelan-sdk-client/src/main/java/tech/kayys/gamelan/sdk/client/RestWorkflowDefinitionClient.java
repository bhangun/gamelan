package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST-based implementation of {@link WorkflowDefinitionClient}.
 * Provides operations for creating, retrieving, listing, and deleting workflow
 * definitions
 * over HTTP.
 */
public class RestWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final GamelanClientConfig config;
    private final WebClient webClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Initializes the REST client with provided configuration.
     * 
     * @param config the client configuration
     * @param vertx  the Mutiny Vert.x instance
     */
    RestWorkflowDefinitionClient(GamelanClientConfig config, Vertx vertx) {
        this.config = config;

        WebClientOptions options = new WebClientOptions()
                .setDefaultHost(config.baseUri().getHost())
                .setDefaultPort(config.baseUri().getPort())
                .setSsl(config.baseUri().getScheme().equalsIgnoreCase("https"))
                .setConnectTimeout((int) config.timeout().toMillis())
                .setIdleTimeout((int) config.timeout().getSeconds());

        this.webClient = WebClient.create(vertx, options);
    }

    /**
     * Helper to create a request with standardized headers and interceptors.
     */
    private HttpRequest<Buffer> createRequest(io.vertx.core.http.HttpMethod method, String path) {
        String fullPath = config.baseUri().getPath() == null || config.baseUri().getPath().isEmpty()
                ? path
                : config.baseUri().getPath() + path;

        HttpRequest<Buffer> request = webClient.request(method, fullPath);

        // Add headers
        request.putHeader("X-Tenant-ID", config.tenantId());
        if (config.apiKey() != null && !config.apiKey().trim().isEmpty()) {
            request.putHeader("Authorization", "Bearer " + config.apiKey());
        }
        config.headers().forEach(request::putHeader);

        // Apply interceptors
        for (ClientInterceptor interceptor : config.interceptors()) {
            request = interceptor.apply(request);
        }

        return request;
    }

    @Override
    public Uni<WorkflowDefinition> createDefinition(WorkflowDefinition request) {
        checkClosed();

        CreateWorkflowDefinitionRequest dto = WorkflowDefinitionMapper.toCreateRequest(request);
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-definitions"), dto)
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to create definition: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), WorkflowDefinition.class);
                });
    }

    @Override
    public Uni<WorkflowDefinition> getDefinition(String definitionId) {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-definitions/" + definitionId)
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to get definition: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), WorkflowDefinition.class);
                });
    }

    @Override
    public Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly) {
        checkClosed();
        String query = activeOnly ? "?activeOnly=true" : "";
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-definitions" + query)
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to list definitions: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<WorkflowDefinition>>() {
                            });
                });
    }

    @Override
    public Uni<Void> deleteDefinition(String definitionId) {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.DELETE, "/api/v1/workflow-definitions/" + definitionId)
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to delete definition: " + response.bodyAsString(), response.statusCode()));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * Helper to serialize and send JSON body.
     */
    private <T> Uni<io.vertx.mutiny.ext.web.client.HttpResponse<Buffer>> sendJson(HttpRequest<Buffer> request, T body) {
        try {
            String json = mapper.writeValueAsString(body);
            return request.sendBuffer(Buffer.buffer(json));
        } catch (Exception e) {
            return Uni.createFrom().failure(new GamelanClientException("Failed to serialize request", e));
        }
    }

    /**
     * Helper to deserialize JSON response.
     */
    private <T> Uni<T> deserialize(String body, Class<T> clazz) {
        try {
            return Uni.createFrom().item(mapper.readValue(body, clazz));
        } catch (Exception e) {
            return Uni.createFrom().failure(new GamelanClientException("Failed to deserialize response", e));
        }
    }

    /**
     * Helper to deserialize JSON response list.
     */
    private <T> Uni<T> deserialize(String body, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
        try {
            return Uni.createFrom().item(mapper.readValue(body, typeRef));
        } catch (Exception e) {
            return Uni.createFrom().failure(new GamelanClientException("Failed to deserialize response", e));
        }
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (webClient != null) {
                webClient.close();
            }
        }
    }
}
