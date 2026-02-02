package tech.kayys.gamelan.sdk.client;

/**
 * Defines the available transport protocols for the Gamelan SDK client.
 */
public enum TransportType {
    /**
     * REST-based communication over HTTP/JSON.
     */
    REST,

    /**
     * gRPC-based communication for high-performance streaming.
     */
    GRPC,

    /**
     * Local execution within the same JVM (useful for testing or embedded use).
     */
    LOCAL
}