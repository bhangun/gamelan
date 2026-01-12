package tech.kayys.silat.sdk.client;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ============================================================================
 * SILAT CLIENT SDK
 * ============================================================================
 *
 * Fluent, type-safe API for interacting with the Silat workflow engine.
 * Supports both REST and gRPC transports.
 *
 * Example Usage:
 * ```java
 * SilatClient client = SilatClient.builder()
 * .restEndpoint("http://localhost:8080")
 * .tenantId("acme-corp")
 * .apiKey("secret-key")
 * .build();
 *
 * WorkflowRun run = client.workflows()
 * .create("order-processing")
 * .input("orderId", "ORDER-123")
 * .input("customerId", "CUST-456")
 * .label("environment", "production")
 * .execute()
 * .await().indefinitely();
 *
 * client.runs()
 * .get(run.runId())
 * .await().indefinitely();
 * ```
 */
public class SilatClient {

    private final SilatClientConfig config;
    private final WorkflowRunClient runClient;
    private final WorkflowDefinitionClient definitionClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private SilatClient(SilatClientConfig config) {
        this.config = config;

        // Initialize transport-specific clients
        if (config.transport() == TransportType.REST) {
            this.runClient = new RestWorkflowRunClient(config);
            this.definitionClient = new RestWorkflowDefinitionClient(config);
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
    public SilatClientConfig config() {
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

        public SilatClient build() {
            // Use the SilatClientConfig builder to create the config with validation
            SilatClientConfig config = SilatClientConfig.builder()
                    .endpoint(endpoint)
                    .tenantId(tenantId)
                    .apiKey(apiKey)
                    .transport(transport)
                    .timeout(timeout)
                    .headers(headers)
                    .build();

            return new SilatClient(config);
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
            throw new IllegalStateException("SilatClient is closed");
        }
    }

    /**
     * Close the client and release resources
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                if (runClient != null) {
                    runClient.close();
                }
            } catch (Exception e) {
                // Log error but don't throw to avoid masking other potential issues
                System.err.println("Error closing run client: " + e.getMessage());
            }

            try {
                if (definitionClient != null) {
                    definitionClient.close();
                }
            } catch (Exception e) {
                // Log error but don't throw to avoid masking other potential issues
                System.err.println("Error closing definition client: " + e.getMessage());
            }
        }
    }
}
