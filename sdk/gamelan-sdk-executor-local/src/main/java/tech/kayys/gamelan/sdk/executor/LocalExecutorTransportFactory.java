package tech.kayys.gamelan.sdk.executor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Factory for creating local transport
 */
@ApplicationScoped
public class LocalExecutorTransportFactory {

    @Inject
    LocalExecutorTransport localTransport;

    @ConfigProperty(name = "gamelan.executor.transport", defaultValue = "LOCAL")
    String transportType;

    public ExecutorTransport createTransport() {
        if ("LOCAL".equalsIgnoreCase(transportType)) {
            return localTransport;
        }

        throw new IllegalArgumentException(
                "Only LOCAL transport is supported in local module. Got: " + transportType);
    }
}