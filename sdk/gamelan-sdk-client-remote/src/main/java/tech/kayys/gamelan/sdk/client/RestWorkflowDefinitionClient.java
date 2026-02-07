package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionMapper;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST-based implementation of {@link WorkflowDefinitionClient}.
 */
public class RestWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final GamelanClientConfig config;
    private final WebClient webClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    RestWorkflowDefinitionClient(GamelanClientConfig config, Vertx vertx) {
        this.config = config;

        WebClientOptions options = new WebClientOptions()
                .setDefaultHost(getHostFromEndpoint(config.endpoint()))
                .setDefaultPort(getPortFromEndpoint(config.endpoint()))
                .setSsl(config.endpoint().toLowerCase().startsWith("https"))
                .setConnectTimeout((int) config.timeout().toMillis())
                .setIdleTimeout((int) config.timeout().getSeconds());

        this.webClient = WebClient.create(vertx, options);
    }

    private String getHostFromEndpoint(String endpoint) {
        if (endpoint.startsWith("http")) {
            return java.net.URI.create(endpoint).getHost();
        }
        int colonIndex = endpoint.indexOf(':');
        if (colonIndex != -1) {
            return endpoint.substring(0, colonIndex);
        }
        return endpoint;
    }

    private int getPortFromEndpoint(String endpoint) {
        if (endpoint.startsWith("http")) {
            java.net.URI uri = java.net.URI.create(endpoint);
            int port = uri.getPort();
            if (port == -1) {
                return uri.getScheme().equals("https") ? 443 : 80;
            }
            return port;
        }
        int colonIndex = endpoint.indexOf(':');
        if (colonIndex != -1) {
            return Integer.parseInt(endpoint.substring(colonIndex + 1));
        }
        return 80;
    }

    private HttpRequest<Buffer> createRequest(io.vertx.core.http.HttpMethod method, String path) {
        HttpRequest<Buffer> request = webClient.request(method, path);
        request.putHeader("X-Tenant-ID", config.tenantId());
        if (config.apiKey() != null && !config.apiKey().trim().isEmpty()) {
            request.putHeader("Authorization", "Bearer " + config.apiKey());
        }
        config.headers().forEach(request::putHeader);
        for (ClientInterceptor interceptor : config.interceptors()) {
            interceptor.intercept(request).await().indefinitely();
        }
        return request;
    }

    @Override
    public Uni<WorkflowDefinition> createWorkflow(WorkflowDefinition request) {
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
    public Uni<WorkflowDefinition> getWorkflow(String definitionId) {
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
    public Uni<WorkflowDefinition> getWorkflowByName(String name) {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-definitions/name/" + name)
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to get definition by name: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), WorkflowDefinition.class);
                });
    }

    @Override
    public Uni<List<WorkflowDefinition>> listWorkflows() {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-definitions")
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
    public Uni<Void> deleteWorkflow(String definitionId) {
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

    private <T> Uni<io.vertx.mutiny.ext.web.client.HttpResponse<Buffer>> sendJson(HttpRequest<Buffer> request, T body) {
        try {
            String json = mapper.writeValueAsString(body);
            return request.sendBuffer(Buffer.buffer(json));
        } catch (Exception e) {
            return Uni.createFrom().failure(new GamelanClientException("Failed to serialize request", e));
        }
    }

    private <T> Uni<T> deserialize(String body, Class<T> clazz) {
        try {
            return Uni.createFrom().item(mapper.readValue(body, clazz));
        } catch (Exception e) {
            return Uni.createFrom().failure(new GamelanClientException("Failed to deserialize response", e));
        }
    }

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
