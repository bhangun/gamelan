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