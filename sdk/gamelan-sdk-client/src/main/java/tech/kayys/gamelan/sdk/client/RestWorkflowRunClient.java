package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import java.util.Map;
import java.util.List;

/**
 * REST-based implementation of {@link WorkflowRunClient}.
 * Uses Vert.x Mutiny WebClient for reactive communication with the Gamelan
 * service.
 */
public class RestWorkflowRunClient implements WorkflowRunClient {

    private final GamelanClientConfig config;
    private final io.vertx.mutiny.ext.web.client.WebClient webClient;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Initializes the REST client with provided configuration.
     * 
     * @param config the client configuration
     * @param vertx  the Vert.x instance to use for communication
     */
    RestWorkflowRunClient(GamelanClientConfig config, io.vertx.mutiny.core.Vertx vertx) {
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
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("reason", reason);
        if (waitingOnNodeId != null) {
            params.put("waitingOnNodeId", waitingOnNodeId);
        }
        return sendJson(
                createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/suspend"),
                params)
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
        tech.kayys.gamelan.engine.run.dto.ResumeRunRequest request = new tech.kayys.gamelan.engine.run.dto.ResumeRunRequest(
                resumeData, humanTaskId);
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/resume"),
                request)
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
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("reason", reason);
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/cancel"),
                params)
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
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("name", signalName);
        if (targetNodeId != null) {
            params.put("targetNodeId", targetNodeId);
        }
        if (payload != null) {
            params.put("payload", payload);
        }
        return sendJson(createRequest(io.vertx.core.http.HttpMethod.POST, "/api/v1/workflow-runs/" + runId + "/signal"),
                params)
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
        HttpRequest<Buffer> request = createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-runs");
        if (workflowId != null) {
            request.addQueryParam("workflowId", workflowId);
        }
        if (status != null) {
            request.addQueryParam("status", status);
        }
        request.addQueryParam("page", String.valueOf(page));
        request.addQueryParam("size", String.valueOf(size));

        return request.send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to query runs: " + response.bodyAsString(), response.statusCode()));
                    }
                    // Jackson can deserialize List<RunResponse> if we use TypeReference
                    try {
                        List<RunResponse> results = mapper.readValue(response.bodyAsString(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<RunResponse>>() {
                                });
                        return Uni.createFrom().item(results);
                    } catch (Exception e) {
                        return Uni.createFrom()
                                .failure(new GamelanClientException("Failed to deserialize query results", e));
                    }
                });
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        checkClosed();
        // Assuming there will be an endpoint or query param for this.
        // For now, let's use query with status=RUNNING and just check size?
        // No, engine has getActiveRunsCount. I'll add an endpoint for it.
        return createRequest(io.vertx.core.http.HttpMethod.GET, "/api/v1/workflow-runs/active-count")
                .send()
                .onItem().transformToUni(response -> {
                    if (response.statusCode() >= 400) {
                        return Uni.createFrom().failure(new GamelanClientException(
                                "Failed to get active count: " + response.bodyAsString(), response.statusCode()));
                    }
                    return Uni.createFrom().item(Long.parseLong(response.bodyAsString().trim()));
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
