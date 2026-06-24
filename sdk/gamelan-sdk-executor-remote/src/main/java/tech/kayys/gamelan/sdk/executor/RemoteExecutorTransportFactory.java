package tech.kayys.gamelan.sdk.executor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.Identifier;

/**
 * Factory for creating remote transports (gRPC or Kafka)
 */
@ApplicationScoped
public class RemoteExecutorTransportFactory {

    @Inject
    @Identifier("grpc")
    RemoteExecutorTransport grpcTransport;

    @Inject
    @Identifier("kafka")
    RemoteExecutorTransport kafkaTransport;

    @ConfigProperty(name = "gamelan.executor.transport", defaultValue = "GRPC")
    String transportType;

    public RemoteExecutorTransport createTransport() {
        if ("GRPC".equalsIgnoreCase(transportType)) {
            return grpcTransport;
        }

        if ("KAFKA".equalsIgnoreCase(transportType)) {
            return kafkaTransport;
        }

        throw new IllegalArgumentException(
                "Unsupported transport type: " + transportType + ". Supported: GRPC, KAFKA");
    }
}
