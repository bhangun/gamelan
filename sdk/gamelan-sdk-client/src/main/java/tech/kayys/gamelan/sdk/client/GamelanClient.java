package tech.kayys.gamelan.sdk.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

/**
 * ============================================================================
 * GAMELAN CLIENT SDK
 * ============================================================================
 * Main entry point for the Gamelan SDK.
 * This client provides a unified, fluent API for interacting with Gamelan
 * workflow
 * definitions and runs across different transport protocols (REST, gRPC, or
 * LOCAL).
 */
public class GamelanClient implements AutoCloseable {

    private final GamelanClientConfig config;
    private final io.vertx.mutiny.core.Vertx vertx;
    private final WorkflowRunClient runClient;
    private final WorkflowDefinitionClient definitionClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Internal constructor used by the Builder.
     * 
     * @param config the client configuration
     */
    public GamelanClient(GamelanClientConfig config) {
        this.config = config;
        this.vertx = config.vertx();

        // Initialize transport-specific clients
        switch (config.transport()) {
            case REST -> {
                this.runClient = new RestWorkflowRunClient(config, vertx);
                this.definitionClient = new RestWorkflowDefinitionClient(config, vertx);
            }
            case LOCAL -> {
                this.runClient = new LocalWorkflowRunClient(config.runManager(), config.tenantId());
                this.definitionClient = new LocalWorkflowDefinitionClient(config.definitionService(),
                        config.tenantId());
            }
            case GRPC -> {
                this.runClient = new GrpcWorkflowRunClient(config);
                this.definitionClient = new GrpcWorkflowDefinitionClient(config);
            }
            default -> throw new IllegalArgumentException("Unsupported transport: " + config.transport());
        }
    }

    /**
     * @return the client configuration
     */
    public GamelanClientConfig config() {
        return config;
    }

    // ==================== BUILDER ====================

    /**
     * Creates a new builder for {@link GamelanClient}.
     * 
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link GamelanClient}.
     */
    public static class Builder {
        private final GamelanClientConfig.Builder configBuilder = GamelanClientConfig.builder();

        /**
         * Sets the generic endpoint.
         * 
         * @param endpoint endpoint string
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            configBuilder.endpoint(endpoint);
            return this;
        }

        /**
         * Sets the REST endpoint.
         * 
         * @param endpoint REST service URL
         * @return this builder
         */
        public Builder restEndpoint(String endpoint) {
            configBuilder.endpoint(endpoint).rest();
            return this;
        }

        /**
         * Sets the gRPC endpoint.
         * 
         * @param endpoint gRPC service address (host:port)
         * @return this builder
         */
        public Builder grpcEndpoint(String endpoint) {
            configBuilder.endpoint(endpoint).grpc();
            return this;
        }

        /**
         * Sets the gRPC endpoint with host and port.
         * 
         * @param host gRPC host
         * @param port gRPC port
         * @return this builder
         */
        public Builder grpcEndpoint(String host, int port) {
            configBuilder.endpoint(host + ":" + port).grpc();
            return this;
        }

        /**
         * Configures the client for local execution using provided services.
         * 
         * @param runManager        engine run manager
         * @param definitionService engine definition service
         * @param tenantId          tenant ID
         * @return this builder
         */
        public Builder local(WorkflowRunManager runManager, WorkflowDefinitionService definitionService,
                String tenantId) {
            configBuilder.endpoint("local").local()
                    .runManager(runManager)
                    .definitionService(definitionService)
                    .tenantId(tenantId);
            return this;
        }

        /**
         * Sets the tenant identifier.
         * 
         * @param tenantId tenant ID
         * @return this builder
         */
        public Builder tenantId(String tenantId) {
            configBuilder.tenantId(tenantId);
            return this;
        }

        /**
         * Sets the API key for authentication.
         * 
         * @param apiKey authentication key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            configBuilder.apiKey(apiKey);
            return this;
        }

        /**
         * Sets the request timeout.
         * 
         * @param timeout timeout duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            configBuilder.timeout(timeout);
            return this;
        }

        /**
         * Adds a custom header.
         * 
         * @param key   header name
         * @param value header value
         * @return this builder
         */
        public Builder header(String key, String value) {
            configBuilder.header(key, value);
            return this;
        }

        /**
         * Provides an external Vertx instance.
         * 
         * @param vertx Mutiny Vertx instance
         * @return this builder
         */
        public Builder vertx(io.vertx.mutiny.core.Vertx vertx) {
            configBuilder.vertx(vertx);
            return this;
        }

        /**
         * Adds a custom interceptor to the request pipeline.
         * 
         * @param interceptor interceptor implementation
         * @return this builder
         */
        public Builder interceptor(ClientInterceptor interceptor) {
            configBuilder.interceptor(interceptor);
            return this;
        }

        /**
         * Provides the local workflow run manager.
         * 
         * @param runManager engine run manager
         * @return this builder
         */
        public Builder runManager(WorkflowRunManager runManager) {
            configBuilder.runManager(runManager);
            return this;
        }

        /**
         * Provides the local workflow definition service.
         * 
         * @param definitionService engine definition service
         * @return this builder
         */
        public Builder definitionService(WorkflowDefinitionService definitionService) {
            configBuilder.definitionService(definitionService);
            return this;
        }

        /**
         * Builds and returns a new {@link GamelanClient}.
         * 
         * @return initialized client
         */
        public GamelanClient build() {
            return new GamelanClient(configBuilder.build());
        }
    }

    // ==================== API METHODS ====================

    /**
     * Entry point for workflow run operations.
     * 
     * @return operations for managing workflow runs
     */
    public WorkflowRunOperations runs() {
        checkClosed();
        return new WorkflowRunOperations(runClient);
    }

    /**
     * Entry point for workflow definition operations.
     * 
     * @return operations for managing workflow definitions
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
     * Shortcut for creating a new workflow run.
     * 
     * @param workflowId the ID of the workflow to run
     * @return a builder for the run creation request
     */
    public CreateRunBuilder createRun(String workflowId) {
        checkClosed();
        return new CreateRunBuilder(runClient, workflowId);
    }

    /**
     * Shortcut for defining a new workflow.
     * 
     * @param name the name of the workflow
     * @return a builder for the workflow definition request
     */
    public WorkflowDefinitionBuilder defineWorkflow(String name) {
        checkClosed();
        return new WorkflowDefinitionBuilder(definitionClient, name);
    }

    /**
     * Closes the client and releases underlying resources.
     * If the Vertx instance was created by this client (managed), it will also be
     * closed.
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
            if (config.managedVertx() && vertx != null) {
                vertx.close();
            }
        }
    }
}
