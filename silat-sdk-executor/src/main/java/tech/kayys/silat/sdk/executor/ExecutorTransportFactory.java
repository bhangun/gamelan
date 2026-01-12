package tech.kayys.silat.sdk.executor;

/**
 * Factory for creating executor transports
 */
@jakarta.enterprise.context.ApplicationScoped
public class ExecutorTransportFactory {

    @jakarta.inject.Inject
    GrpcExecutorTransport grpcTransport;

    @jakarta.inject.Inject
    KafkaExecutorTransport kafkaTransport;

    public ExecutorTransport createTransport() {
        String transportType = System.getenv()
                .getOrDefault("EXECUTOR_TRANSPORT", "GRPC");

        return switch (transportType.toUpperCase()) {
            case "KAFKA" -> kafkaTransport;
            case "GRPC" -> grpcTransport;
            default -> throw new IllegalArgumentException(
                    "Unknown transport: " + transportType);
        };
    }
}
