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
