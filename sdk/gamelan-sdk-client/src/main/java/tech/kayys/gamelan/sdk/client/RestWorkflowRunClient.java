package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import java.util.Map;
import java.util.List;

/**
 * REST-based workflow run client
 */
public class RestWorkflowRunClient implements WorkflowRunClient {

    private final GamelanClientConfig config;
    private final io.vertx.mutiny.ext.web.client.WebClient webClient;
    private final io.vertx.mutiny.core.Vertx vertx;

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    RestWorkflowRunClient(GamelanClientConfig config, io.vertx.mutiny.core.Vertx vertx) {
        this.config = config;
        this.vertx = vertx;

        System.out.println("RestWorkflowRunClient initialized with endpoint: '" + config.endpoint() + "'");
        System.out.println("Host: " + getHostFromEndpoint(config.endpoint()));
        System.out.println("Port: " + getPortFromEndpoint(config.endpoint()));

        io.vertx.ext.web.client.WebClientOptions options = new io.vertx.ext.web.client.WebClientOptions()
                .setDefaultHost(getHostFromEndpoint(config.endpoint()))
                .setDefaultPort(getPortFromEndpoint(config.endpoint()))
                .setSsl(config.endpoint().toLowerCase().startsWith("https"));

        this.webClient = io.vertx.mutiny.ext.web.client.WebClient.create(vertx, options);
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

    @Override
    public Uni<RunResponse> createRun(CreateRunRequest request) {
        return webClient.post("/api/v1/workflow-runs")
                .putHeader("X-Tenant-ID", config.tenantId())
                .putHeader("Authorization", "Bearer " + config.apiKey())
                .sendJson(request)
                .map(response -> {
                    System.out.println("RestWorkflowRunClient: createRun response status: " + response.statusCode());
                    System.out.println("RestWorkflowRunClient: createRun response body: " + response.bodyAsString());

                    io.vertx.core.json.JsonObject json = response.bodyAsJsonObject();
                    if (json == null) {
                        System.out.println("RestWorkflowRunClient: JSON is null!");
                        return null;
                    }

                    Object idObj = json.getValue("id");
                    String runId = (idObj instanceof io.vertx.core.json.JsonObject)
                            ? ((io.vertx.core.json.JsonObject) idObj).getString("value")
                            : (String) idObj;

                    Object defIdObj = json.getValue("definitionId");
                    String workflowId = (defIdObj instanceof io.vertx.core.json.JsonObject)
                            ? ((io.vertx.core.json.JsonObject) defIdObj).getString("value")
                            : (json.getString("workflowId") != null ? json.getString("workflowId") : (String) defIdObj);

                    RunResponse runResponse = RunResponse.builder()
                            .runId(runId)
                            .status(json.getString("status"))
                            .workflowId(workflowId)
                            .build();

                    System.out.println("RestWorkflowRunClient: Mapped RunResponse: id=" + runResponse.getRunId());
                    return runResponse;
                });
    }

    // Implement other methods similarly...

    @Override
    public Uni<RunResponse> getRun(String runId) {
        return webClient.get("/api/v1/workflow-runs/" + runId)
                .putHeader("X-Tenant-ID", config.tenantId())
                .putHeader("Authorization", "Bearer " + config.apiKey())
                .send()
                .map(response -> {
                    try {
                        return mapper.readValue(response.bodyAsString(), RunResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize RunResponse: " + e.getMessage(), e);
                    }
                });
    }

    // ... (other methods)

    @Override
    public Uni<RunResponse> startRun(String runId) {
        return webClient.post("/api/v1/workflow-runs/" + runId + "/start")
                .putHeader("X-Tenant-ID", config.tenantId())
                .putHeader("Authorization", "Bearer " + config.apiKey())
                .send()
                .map(response -> {
                    try {
                        return mapper.readValue(response.bodyAsString(), RunResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize RunResponse: " + e.getMessage(), e);
                    }
                });
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        return null;
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        return null;
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        return null;
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        return null;
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        return webClient.get("/api/v1/workflow-runs/" + runId + "/history")
                .putHeader("X-Tenant-ID", config.tenantId())
                .putHeader("Authorization", "Bearer " + config.apiKey())
                .send()
                .map(response -> {
                    try {
                        return mapper.readValue(response.bodyAsString(), ExecutionHistory.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize ExecutionHistory: " + e.getMessage(), e);
                    }
                });
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        return null;
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        return null;
    }

    @Override
    public void close() {
        if (webClient != null) {
            webClient.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }
}
