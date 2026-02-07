package tech.kayys.gamelan.sdk.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.vertx.mutiny.core.Vertx;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

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
    private final URI baseUri;
    private final Vertx vertx;
    private final boolean managedVertx;
    private final List<ClientInterceptor> interceptors;
    private final WorkflowRunManager runManager;
    private final WorkflowDefinitionService definitionService;

    private GamelanClientConfig(
            String endpoint,
            String tenantId,
            String apiKey,
            TransportType transport,
            Duration timeout,
            Map<String, String> headers,
            Vertx vertx,
            List<ClientInterceptor> interceptors,
            WorkflowRunManager runManager,
            WorkflowDefinitionService definitionService) {
        this.endpoint = endpoint;
        this.tenantId = tenantId;
        this.apiKey = apiKey;
        this.transport = transport;
        this.timeout = timeout;
        this.headers = headers != null ? Collections.unmodifiableMap(headers) : Map.of();
        this.interceptors = interceptors != null ? Collections.unmodifiableList(new ArrayList<>(interceptors))
                : List.of();
        this.runManager = runManager;
        this.definitionService = definitionService;

        // Robust URI parsing
        if (endpoint != null && endpoint.startsWith("http")) {
            this.baseUri = URI.create(endpoint);
        } else if (endpoint != null && !endpoint.equals("local")) {
            // Assume host:port if no scheme and not local
            this.baseUri = URI.create("http://" + endpoint);
        } else {
            this.baseUri = null;
        }

        if (vertx != null) {
            this.vertx = vertx;
            this.managedVertx = false;
        } else {
            this.vertx = Vertx.vertx();
            this.managedVertx = true;
        }
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

    public URI baseUri() {
        return baseUri;
    }

    public Vertx vertx() {
        return vertx;
    }

    public boolean managedVertx() {
        return managedVertx;
    }

    public List<ClientInterceptor> interceptors() {
        return interceptors;
    }

    public WorkflowRunManager runManager() {
        return runManager;
    }

    public WorkflowDefinitionService definitionService() {
        return definitionService;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String endpoint;
        private String tenantId;
        private String apiKey;
        private TransportType transport = TransportType.REST;
        private Duration timeout = Duration.ofSeconds(30);
        private Map<String, String> headers = new java.util.HashMap<>();
        private Vertx vertx;
        private List<ClientInterceptor> interceptors = new ArrayList<>();
        private WorkflowRunManager runManager;
        private WorkflowDefinitionService definitionService;

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

        public Builder vertx(Vertx vertx) {
            this.vertx = vertx;
            return this;
        }

        public Builder interceptor(ClientInterceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        public Builder runManager(WorkflowRunManager runManager) {
            this.runManager = runManager;
            return this;
        }

        public Builder definitionService(WorkflowDefinitionService definitionService) {
            this.definitionService = definitionService;
            return this;
        }

        public GamelanClientConfig build() {
            Objects.requireNonNull(endpoint, "Endpoint cannot be null");
            Objects.requireNonNull(tenantId, "Tenant ID cannot be null");

            return new GamelanClientConfig(endpoint, tenantId, apiKey, transport, timeout, headers, vertx,
                    interceptors, runManager, definitionService);
        }

        public Builder rest() {
            this.transport = TransportType.REST;
            return this;
        }

        public Builder grpc() {
            this.transport = TransportType.GRPC;
            return this;
        }

        public Builder local() {
            this.transport = TransportType.LOCAL;
            return this;
        }
    }
}
