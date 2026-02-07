package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST-based implementation of {@link WorkflowRunClient}.
 */
public class RestWorkflowRunClient implements WorkflowRunClient {

    private final GamelanClientConfig config;
    private final WebClient webClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    RestWorkflowRunClient(GamelanClientConfig config, Vertx vertx) {
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
    public Uni<RunResponse> createRun(CreateRunRequest request) {
        checkClosed();
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs"), request)
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to create run: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), RunResponse.class);
                });
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-runs/" + runId)
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to get run: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), RunResponse.class);
                });
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/start")
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to start run: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), RunResponse.class);
                });
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        checkClosed();
        JsonObject body = new JsonObject().put("reason", reason).put("waitingOnNodeId", waitingOnNodeId);
        return sendJson(
                createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/suspend"), body)
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to suspend run: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), RunResponse.class);
                });
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        checkClosed();
        JsonObject body = new JsonObject().put("resumeData", new JsonObject(resumeData)).put("humanTaskId",
                humanTaskId);
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/resume"),
                body)
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to resume run: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), RunResponse.class);
                });
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        checkClosed();
        JsonObject body = new JsonObject().put("reason", reason);
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/cancel"),
                body)
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to cancel run: " + response.bodyAsString(), response.statusCode()));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        checkClosed();
        JsonObject body = new JsonObject()
                .put("signalName", signalName)
                .put("targetNodeId", targetNodeId)
                .put("payload", new JsonObject(payload));
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/signal"),
                body)
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to send signal: " + response.bodyAsString(), response.statusCode()));
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-runs/" + runId + "/history")
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to get history: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(), ExecutionHistory.class);
                });
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        checkClosed();
        String path = String.format("/api/v1/workflow-runs?page=%d&size=%d", page, size);
        if (workflowId != null)
            path += "&workflowId=" + workflowId;
        if (status != null)
            path += "&status=" + status;

        return createRequest(io.vertx.core.http.HttpMethod.GET, path)
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to query runs: " + response.bodyAsString(), response.statusCode()));
                    }
                    return deserialize(response.bodyAsString(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<RunResponse>>() {
                            });
                });
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        checkClosed();
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-runs/count")
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to get count: " + response.bodyAsString(), response.statusCode()));
                    }
                    return Uni.createFrom().item(Long.parseLong(response.bodyAsString()));
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
