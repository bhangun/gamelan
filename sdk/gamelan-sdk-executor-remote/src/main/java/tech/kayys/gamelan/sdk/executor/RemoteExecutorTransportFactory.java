package tech.kayys.gamelan.sdk.executor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.Identifier;

/**
 * Factory for creating remote transports (gRPC or Kafka)
 */
@ApplicationScoped
public class RemoteExecutorTransportFactory {

    @Inject
    @Identifier("grpc")
    ExecutorTransport grpcTransport;

    @Inject
    @Identifier("kafka")
    ExecutorTransport kafkaTransport;

    @ConfigProperty(name = "gamelan.executor.transport", defaultValue = "GRPC")
    String transportType;

    public RemoteExecutorTransport createTransport() {
        if ("GRPC".equalsIgnoreCase(transportType)) {
            if (!(grpcTransport instanceof RemoteExecutorTransport)) {
                throw new IllegalStateException("gRPC transport must implement RemoteExecutorTransport");
            }
            return (RemoteExecutorTransport) grpcTransport;
        }

        if ("KAFKA".equalsIgnoreCase(transportType)) {
            if (!(kafkaTransport instanceof RemoteExecutorTransport)) {
                throw new IllegalStateException("Kafka transport must implement RemoteExecutorTransport");
            }
            return (RemoteExecutorTransport) kafkaTransport;
        }

        throw new IllegalArgumentException(
                "Unsupported transport type: " + transportType + ". Supported: GRPC, KAFKA");
    }
}