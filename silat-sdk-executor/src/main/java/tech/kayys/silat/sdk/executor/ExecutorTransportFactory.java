package tech.kayys.silat.sdk.executor;

/**
 * Factory for creating executor transports
 */
@jakarta.enterprise.context.ApplicationScoped
public class ExecutorTransportFactory {

    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<ExecutorTransport> availableTransports;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "silat.executor.transport", defaultValue = "GRPC")
    String transportType;

    public ExecutorTransport createTransport() {
        for (ExecutorTransport transport : availableTransports) {
            if (transport.getCommunicationType().name().equalsIgnoreCase(transportType)) {
                return transport;
            }
        }

        throw new IllegalArgumentException(
                "Unknown or unavailable transport: " + transportType + ". Available: " +
                        availableTransports.stream().map(t -> t.getCommunicationType().name()).toList());
    }
}
