Coto Output
Generated: 2026-02-02 14:17:41
Files: 19 | Directories: 17 | Total Size: 51.6 KB


================================================================================
main/java/tech/kayys/gamelan/sdk/client/CreateRunBuilder.java
Size: 2.8 KB | Modified: 2026-01-19 15:06:36
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Builder for creating workflow runs
 */
public class CreateRunBuilder {

    private final WorkflowRunClient client;
    private final String workflowDefinitionId;
    private final Map<String, Object> inputs = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private String workflowVersion = "1.0.0";
    private String correlationId;
    private boolean autoStart = false;

    CreateRunBuilder(WorkflowRunClient client, String workflowDefinitionId) {
        this.client = client;
        this.workflowDefinitionId = workflowDefinitionId;
    }

    public CreateRunBuilder version(String version) {
        this.workflowVersion = version;
        return this;
    }

    public CreateRunBuilder input(String key, Object value) {
        inputs.put(key, value);
        return this;
    }

    public CreateRunBuilder inputs(Map<String, Object> inputs) {
        this.inputs.putAll(inputs);
        return this;
    }

    public CreateRunBuilder correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public CreateRunBuilder autoStart(boolean autoStart) {
        this.autoStart = autoStart;
        return this;
    }

    public CreateRunBuilder label(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Label key cannot be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("Label value cannot be null");
        }
        this.labels.put(key, value);
        return this;
    }

    public CreateRunBuilder labels(Map<String, String> labels) {
        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                label(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    /**
     * Get the labels map for validation or debugging purposes
     */
    public Map<String, String> getLabels() {
        return new HashMap<>(labels);
    }

    /**
     * Execute and return the created run
     */
    public Uni<RunResponse> execute() {
        CreateRunRequest request = new CreateRunRequest(
                workflowDefinitionId,
                workflowVersion,
                inputs,
                correlationId,
                autoStart);
        return client.createRun(request);
    }

    /**
     * Execute and immediately start the run
     */
    public Uni<RunResponse> executeAndStart() {
        this.autoStart = true;
        return execute();
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/GamelanClient.java
Size: 4.2 KB | Modified: 2026-01-19 13:53:36
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ============================================================================
 * GAMELAN CLIENT SDK
 * ============================================================================
 */
public class GamelanClient implements AutoCloseable {

    private final GamelanClientConfig config;
    private final io.vertx.mutiny.core.Vertx vertx;
    private final WorkflowRunClient runClient;
    private final WorkflowDefinitionClient definitionClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private GamelanClient(GamelanClientConfig config) {
        this.config = config;
        this.vertx = io.vertx.mutiny.core.Vertx.vertx();

        // Initialize transport-specific clients
        if (config.transport() == TransportType.REST) {
            this.runClient = new RestWorkflowRunClient(config, vertx);
            this.definitionClient = new RestWorkflowDefinitionClient(config, vertx);
        } else if (config.transport() == TransportType.GRPC) {
            this.runClient = new GrpcWorkflowRunClient(config);
            this.definitionClient = new GrpcWorkflowDefinitionClient(config);
        } else {
            throw new IllegalArgumentException("Unsupported transport: " + config.transport());
        }
    }

    /**
     * Get the client configuration
     */
    public GamelanClientConfig config() {
        return config;
    }

    // ==================== BUILDER ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String endpoint;
        private String tenantId;
        private String apiKey;
        private TransportType transport = TransportType.REST;
        private Duration timeout = Duration.ofSeconds(30);
        private Map<String, String> headers = new HashMap<>();

        public Builder restEndpoint(String endpoint) {
            this.endpoint = endpoint;
            this.transport = TransportType.REST;
            return this;
        }

        public Builder grpcEndpoint(String host, int port) {
            this.endpoint = host + ":" + port;
            this.transport = TransportType.GRPC;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public GamelanClient build() {
            GamelanClientConfig config = GamelanClientConfig.builder()
                    .endpoint(endpoint)
                    .tenantId(tenantId)
                    .apiKey(apiKey)
                    .transport(transport)
                    .timeout(timeout)
                    .headers(headers)
                    .build();

            return new GamelanClient(config);
        }
    }

    // ==================== API METHODS ====================

    /**
     * Access workflow run operations
     */
    public WorkflowRunOperations runs() {
        checkClosed();
        return new WorkflowRunOperations(runClient);
    }

    /**
     * Access workflow definition operations
     */
    public WorkflowDefinitionOperations workflows() {
        checkClosed();
        return new WorkflowDefinitionOperations(definitionClient);
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("GamelanClient is closed");
        }
    }

    /**
     * Close the client and release resources
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (runClient != null) {
                runClient.close();
            }
            if (definitionClient != null) {
                definitionClient.close();
            }
            if (vertx != null) {
                vertx.close();
            }
        }
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/GamelanClientConfig.java
Size: 4.4 KB | Modified: 2026-01-19 10:11:01
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the Gamelan client.
 * This class holds all the necessary configuration parameters for connecting to
 * the Gamelan service.
 */
public final class GamelanClientConfig {
    private final String endpoint;
    private final String tenantId;
    private final String apiKey;
    private final TransportType transport;
    private final Duration timeout;
    private final Map<String, String> headers;

    private GamelanClientConfig(String endpoint, String tenantId, String apiKey,
            TransportType transport, Duration timeout, Map<String, String> headers) {
        this.endpoint = endpoint;
        this.tenantId = tenantId;
        this.apiKey = apiKey;
        this.transport = transport;
        this.timeout = timeout;
        this.headers = headers != null ? Collections.unmodifiableMap(headers) : Map.of();
    }

    // Getters
    public String endpoint() {
        return endpoint;
    }

    public String tenantId() {
        return tenantId;
    }

    public String apiKey() {
        return apiKey;
    }

    public TransportType transport() {
        return transport;
    }

    public Duration timeout() {
        return timeout;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static GamelanClientConfig defaultConfig(String endpoint, String tenantId) {
        return builder()
                .endpoint(endpoint)
                .tenantId(tenantId)
                .transport(TransportType.REST)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    public static class Builder {
        private String endpoint;
        private String tenantId;
        private String apiKey;
        private TransportType transport = TransportType.REST;
        private Duration timeout = Duration.ofSeconds(30);
        private Map<String, String> headers = new java.util.HashMap<>();

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder transport(TransportType transport) {
            this.transport = transport;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public GamelanClientConfig build() {
            Objects.requireNonNull(endpoint, "Endpoint cannot be null");
            Objects.requireNonNull(tenantId, "Tenant ID cannot be null");
            Objects.requireNonNull(transport, "Transport type cannot be null");
            Objects.requireNonNull(timeout, "Timeout cannot be null");

            if (endpoint.trim().isEmpty()) {
                throw new IllegalArgumentException("Endpoint cannot be empty");
            }
            if (tenantId.trim().isEmpty()) {
                throw new IllegalArgumentException("Tenant ID cannot be empty");
            }
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Timeout must be positive");
            }
            if (apiKey != null && apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("API key cannot be empty when provided");
            }

            return new GamelanClientConfig(endpoint, tenantId, apiKey, transport, timeout, headers);
        }

        public Builder rest() {
            this.transport = TransportType.REST;
            return this;
        }

        public Builder grpc() {
            this.transport = TransportType.GRPC;
            return this;
        }

        public Builder timeoutSeconds(long seconds) {
            this.timeout = Duration.ofSeconds(seconds);
            return this;
        }
    }
}
================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/GrpcWorkflowDefinitionClient.java
Size: 1.5 KB | Modified: 2026-01-19 15:07:03
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC-based workflow definition client
 */
class GrpcWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final GamelanClientConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    GrpcWorkflowDefinitionClient(GamelanClientConfig config) {
        this.config = config;
    }

    /**
     * Get the client configuration
     */
    public GamelanClientConfig config() {
        return config;
    }

    // Implement using gRPC stubs...

    @Override
    public Uni<WorkflowDefinition> createDefinition(WorkflowDefinition request) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<WorkflowDefinition> getDefinition(String definitionId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Void> deleteDefinition(String definitionId) {
        checkClosed();
        return null;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Close gRPC resources if needed
        }
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/GrpcWorkflowRunClient.java
Size: 2.5 KB | Modified: 2026-01-19 19:05:10
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC-based workflow run client
 */
class GrpcWorkflowRunClient implements WorkflowRunClient {

    private final GamelanClientConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // gRPC stub would be injected here

    GrpcWorkflowRunClient(GamelanClientConfig config) {
        this.config = config;
    }

    /**
     * Get the client configuration
     */
    public GamelanClientConfig config() {
        return config;
    }

    @Override
    public Uni<RunResponse> createRun(CreateRunRequest request) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        checkClosed();
        return null;
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        checkClosed();
        return null;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Close gRPC resources if needed
        }
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/QueryRunsBuilder.java
Size: 1010 B | Modified: 2026-01-19 15:07:00
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import java.util.List;

/**
 * Builder for querying runs
 */
public class QueryRunsBuilder {

    private final WorkflowRunClient client;
    private String workflowId;
    private String status;
    private int page = 0;
    private int size = 20;

    QueryRunsBuilder(WorkflowRunClient client) {
        this.client = client;
    }

    public QueryRunsBuilder workflowId(String workflowId) {
        this.workflowId = workflowId;
        return this;
    }

    public QueryRunsBuilder status(String status) {
        this.status = status;
        return this;
    }

    public QueryRunsBuilder page(int page) {
        this.page = page;
        return this;
    }

    public QueryRunsBuilder size(int size) {
        this.size = size;
        return this;
    }

    public Uni<List<RunResponse>> execute() {
        return client.queryRuns(workflowId, status, page, size);
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/RestWorkflowDefinitionClient.java
Size: 8.8 KB | Modified: 2026-01-19 15:11:35
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.core.json.JsonObject;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST-based workflow definition client
 */
public class RestWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final GamelanClientConfig config;
    private final Vertx vertx;
    private final WebClient webClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    RestWorkflowDefinitionClient(GamelanClientConfig config, Vertx vertx) {
        this.config = config;
        this.vertx = vertx;

        System.out.println("RestWorkflowDefinitionClient initialized with endpoint: '" + config.endpoint() + "'");
        System.out.println("Host: " + getHostFromEndpoint(config.endpoint()));
        System.out.println("Port: " + getPortFromEndpoint(config.endpoint()));

        // Use proper configuration
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

        CreateWorkflowDefinitionRequest dto = WorkflowDefinitionMapper
                .toCreateRequest(request);
        JsonObject requestBody;
        try {
            requestBody = new JsonObject(mapper.writeValueAsString(dto));
        } catch (Exception e) {
            return Uni.createFrom().failure(new RuntimeException("Failed to serialize request: " + e.getMessage(), e));
        }

        return applyAuthHeaders(webClient
                .post(getPath("/api/v1/workflow-definitions"))
                .putHeader("Content-Type", "application/json")
                .putHeader("X-Tenant-ID", config.tenantId()))
                .sendJson(requestBody)
                .onItem().transform(response -> {
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        String body = response.bodyAsString();
                        try {
                            return mapper.readValue(body, WorkflowDefinition.class);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to deserialize workflow definition: " + e.getMessage(),
                                    e);
                        }
                    }
                    throw new RuntimeException("Failed to create workflow definition: [" + response.statusCode() + "] "
                            + response.statusMessage() + " - " + response.bodyAsString());
                })
                .onFailure().transform(
                        msg -> new RuntimeException("Failed to create workflow definition: " + msg.getMessage(), msg));
    }

    @Override
    public Uni<WorkflowDefinition> getDefinition(String definitionId) {
        return applyAuthHeaders(webClient
                .get(getPath("/api/v1/workflow-definitions/" + definitionId))
                .putHeader("Accept", "application/json")
                .putHeader("X-Tenant-ID", config.tenantId()))
                .send()
                .onItem().transform(response -> {
                    try {
                        return mapper.readValue(response.bodyAsString(), WorkflowDefinition.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize workflow definition: " + e.getMessage(), e);
                    }
                })
                .onFailure().recoverWithUni(failure -> Uni.createFrom().failure(
                        new RuntimeException("Failed to get workflow definition: " + failure.getMessage(), failure)));
    }

    @Override
    public Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly) {
        String query = activeOnly ? "?activeOnly=true" : "";
        return applyAuthHeaders(webClient
                .get(getPath("/api/v1/workflow-definitions" + query))
                .putHeader("Accept", "application/json")
                .putHeader("X-Tenant-ID", config.tenantId()))
                .send()
                .onItem().transform(response -> {
                    try {
                        return mapper.readValue(response.bodyAsString(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<WorkflowDefinition>>() {
                                });
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize workflow definitions: " + e.getMessage(), e);
                    }
                })
                .onFailure().recoverWithUni(failure -> Uni.createFrom().failure(
                        new RuntimeException("Failed to list workflow definitions: " + failure.getMessage(), failure)));
    }

    @Override
    public Uni<Void> deleteDefinition(String definitionId) {
        return applyAuthHeaders(webClient
                .delete(getPath("/api/v1/workflow-definitions/" + definitionId))
                .putHeader("X-Tenant-ID", config.tenantId()))
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

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/RestWorkflowRunClient.java
Size: 7.5 KB | Modified: 2026-01-19 15:11:37
--------------------------------------------------------------------------------
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

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/ResumeRunBuilder.java
Size: 1.0 KB | Modified: 2026-01-19 15:06:59
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.util.HashMap;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;

/**
 * Builder for resuming runs
 */
public class ResumeRunBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private final Map<String, Object> resumeData = new HashMap<>();
    private String humanTaskId;

    ResumeRunBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    public ResumeRunBuilder data(String key, Object value) {
        resumeData.put(key, value);
        return this;
    }

    public ResumeRunBuilder data(Map<String, Object> data) {
        this.resumeData.putAll(data);
        return this;
    }

    public ResumeRunBuilder humanTaskId(String taskId) {
        this.humanTaskId = taskId;
        return this;
    }

    public Uni<RunResponse> execute() {
        return client.resumeRun(runId, resumeData, humanTaskId);
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/SignalBuilder.java
Size: 1.1 KB | Modified: 2026-01-19 10:10:37
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.util.HashMap;
import java.util.Map;

import io.smallrye.mutiny.Uni;

/**
 * Builder for sending signals
 */
public class SignalBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private String signalName;
    private String targetNodeId;
    private final Map<String, Object> payload = new HashMap<>();

    SignalBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    public SignalBuilder name(String signalName) {
        this.signalName = signalName;
        return this;
    }

    public SignalBuilder targetNode(String nodeId) {
        this.targetNodeId = nodeId;
        return this;
    }

    public SignalBuilder payload(String key, Object value) {
        payload.put(key, value);
        return this;
    }

    public SignalBuilder payload(Map<String, Object> payload) {
        this.payload.putAll(payload);
        return this;
    }

    public Uni<Void> send() {
        return client.signal(runId, signalName, targetNodeId, payload);
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/SuspendRunBuilder.java
Size: 816 B | Modified: 2026-01-19 15:07:02
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;

/**
 * Builder for suspending runs
 */
public class SuspendRunBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private String reason;
    private String waitingOnNodeId;

    SuspendRunBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    public SuspendRunBuilder reason(String reason) {
        this.reason = reason;
        return this;
    }

    public SuspendRunBuilder waitingOnNode(String nodeId) {
        this.waitingOnNodeId = nodeId;
        return this;
    }

    public Uni<RunResponse> execute() {
        return client.suspendRun(runId, reason, waitingOnNodeId);
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/TransportType.java
Size: 242 B | Modified: 2026-01-19 10:11:01
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

/**
 * Transport protocol type for the Gamelan client.
 */
public enum TransportType {
    /**
     * REST transport protocol
     */
    REST,

    /**
     * gRPC transport protocol
     */
    GRPC
}
================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/WorkflowDefinitionBuilder.java
Size: 3.3 KB | Modified: 2026-01-19 15:06:26
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowMetadata;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;

/**
 * Builder for workflow definitions
 */
public class WorkflowDefinitionBuilder {

    private final WorkflowDefinitionClient client;
    private final String name;
    private String version = "1.0.0";
    private String tenantId = "default";
    private String description;
    private final List<NodeDefinition> nodes = new ArrayList<>();
    private final Map<String, InputDefinition> inputs = new HashMap<>();
    private final Map<String, OutputDefinition> outputs = new HashMap<>();
    private RetryPolicy retryPolicy;
    private CompensationPolicy compensationPolicy;
    private final Map<String, String> labels = new HashMap<>();

    WorkflowDefinitionBuilder(WorkflowDefinitionClient client, String name) {
        this.client = client;
        this.name = name;
    }

    public WorkflowDefinitionBuilder version(String version) {
        this.version = version;
        return this;
    }

    public WorkflowDefinitionBuilder tenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    public WorkflowDefinitionBuilder description(String description) {
        this.description = description;
        return this;
    }

    public WorkflowDefinitionBuilder addNode(NodeDefinition node) {
        nodes.add(node);
        return this;
    }

    public WorkflowDefinitionBuilder addInput(String name, InputDefinition input) {
        inputs.put(name, input);
        return this;
    }

    public WorkflowDefinitionBuilder addOutput(String name, OutputDefinition output) {
        outputs.put(name, output);
        return this;
    }

    public WorkflowDefinitionBuilder retryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }

    public WorkflowDefinitionBuilder compensationPolicy(CompensationPolicy policy) {
        this.compensationPolicy = policy;
        return this;
    }

    public WorkflowDefinitionBuilder label(String key, String value) {
        labels.put(key, value);
        return this;
    }

    public Uni<WorkflowDefinition> execute() {
        WorkflowMetadata metadata = new WorkflowMetadata(
                labels,
                new HashMap<>(), // annotations
                Instant.now(),
                "sdk-client");

        WorkflowDefinition request = new WorkflowDefinition(
                WorkflowDefinitionId.of(name),
                TenantId.of(tenantId),
                name,
                version,
                description,
                nodes,
                inputs,
                outputs,
                metadata,
                retryPolicy,
                compensationPolicy);
        return client.createDefinition(request);
    }
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/WorkflowDefinitionClient.java
Size: 564 B | Modified: 2026-01-19 15:05:03
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.util.List;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Workflow definition client interface
 */
interface WorkflowDefinitionClient extends AutoCloseable {
    Uni<WorkflowDefinition> createDefinition(WorkflowDefinition request);

    Uni<WorkflowDefinition> getDefinition(String definitionId);

    Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly);

    Uni<Void> deleteDefinition(String definitionId);

    @Override
    void close();
}

================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/WorkflowDefinitionOperations.java
Size: 1.1 KB | Modified: 2026-01-19 15:06:41
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.util.List;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Fluent API for workflow definition operations
 */
public class WorkflowDefinitionOperations {

    private final WorkflowDefinitionClient client;

    WorkflowDefinitionOperations(WorkflowDefinitionClient client) {
        this.client = client;
    }

    /**
     * Create a new workflow definition
     */
    public WorkflowDefinitionBuilder create(String name) {
        return new WorkflowDefinitionBuilder(client, name);
    }

    /**
     * Get a workflow definition
     */
    public Uni<WorkflowDefinition> get(String definitionId) {
        return client.getDefinition(definitionId);
    }

    /**
     * List workflow definitions
     */
    public Uni<List<WorkflowDefinition>> list() {
        return client.listDefinitions(true);
    }

    /**
     * Delete a workflow definition
     */
    public Uni<Void> delete(String definitionId) {
        return client.deleteDefinition(definitionId);
    }
}
================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/WorkflowRunClient.java
Size: 1.1 KB | Modified: 2026-01-19 15:05:36
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import java.util.Map;
import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

/**
 * Workflow run client interface (transport-agnostic)
 */
interface WorkflowRunClient extends AutoCloseable {
    Uni<RunResponse> createRun(CreateRunRequest request);

    Uni<RunResponse> getRun(String runId);

    Uni<RunResponse> startRun(String runId);

    Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId);

    Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId);

    Uni<Void> cancelRun(String runId, String reason);

    Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload);

    Uni<ExecutionHistory> getExecutionHistory(String runId);

    Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size);

    Uni<Long> getActiveRunsCount();

    @Override
    void close();
}
================================================================================

================================================================================
main/java/tech/kayys/gamelan/sdk/client/WorkflowRunOperations.java
Size: 1.9 KB | Modified: 2026-01-19 15:06:39
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

/**
 * Fluent API for workflow run operations
 */
public class WorkflowRunOperations {

    private final WorkflowRunClient client;

    WorkflowRunOperations(WorkflowRunClient client) {
        this.client = client;
    }

    /**
     * Create a new workflow run
     */
    public CreateRunBuilder create(String workflowDefinitionId) {
        return new CreateRunBuilder(client, workflowDefinitionId);
    }

    /**
     * Get a workflow run
     */
    public Uni<RunResponse> get(String runId) {
        return client.getRun(runId);
    }

    /**
     * Start a workflow run
     */
    public Uni<RunResponse> start(String runId) {
        return client.startRun(runId);
    }

    /**
     * Suspend a workflow run
     */
    public SuspendRunBuilder suspend(String runId) {
        return new SuspendRunBuilder(client, runId);
    }

    /**
     * Resume a workflow run
     */
    public ResumeRunBuilder resume(String runId) {
        return new ResumeRunBuilder(client, runId);
    }

    /**
     * Cancel a workflow run
     */
    public Uni<Void> cancel(String runId, String reason) {
        return client.cancelRun(runId, reason);
    }

    /**
     * Send signal to workflow run
     */
    public SignalBuilder signal(String runId) {
        return new SignalBuilder(client, runId);
    }

    /**
     * Get execution history
     */
    public Uni<ExecutionHistory> getHistory(String runId) {
        return client.getExecutionHistory(runId);
    }

    /**
     * Query workflow runs
     */
    public QueryRunsBuilder query() {
        return new QueryRunsBuilder(client);
    }

    /**
     * Get active runs count
     */
    public Uni<Long> getActiveCount() {
        return client.getActiveRunsCount();
    }
}

================================================================================

================================================================================
test/java/tech/kayys/gamelan/sdk/client/GamelanClientTest.java
Size: 1.6 KB | Modified: 2026-01-19 10:11:01
--------------------------------------------------------------------------------
package tech.kayys.gamelan.sdk.client;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

public class GamelanClientTest {

    @Test
    void testBuilderAndConfig() {
        GamelanClient client = GamelanClient.builder()
                .restEndpoint("http://localhost:8080")
                .tenantId("test-tenant")
                .apiKey("test-key")
                .timeout(Duration.ofSeconds(60))
                .header("Custom-Header", "Value")
                .build();

        assertNotNull(client.config());
        assertEquals("http://localhost:8080", client.config().endpoint());
        assertEquals("test-tenant", client.config().tenantId());
        assertEquals("test-key", client.config().apiKey());
        assertEquals(Duration.ofSeconds(60), client.config().timeout());
        assertEquals("Value", client.config().headers().get("Custom-Header"));
        assertEquals(TransportType.REST, client.config().transport());

        client.close();
    }

    @Test
    void testCloseAndState() {
        GamelanClient client = GamelanClient.builder()
                .restEndpoint("http://localhost:8080")
                .tenantId("test-tenant")
                .build();

        // Should work
        assertNotNull(client.runs());
        assertNotNull(client.workflows());

        client.close();

        // Should throw IllegalStateException
        assertThrows(IllegalStateException.class, client::runs);
        assertThrows(IllegalStateException.class, client::workflows);

        // Double close should be safe
        assertDoesNotThrow(client::close);
    }
}
