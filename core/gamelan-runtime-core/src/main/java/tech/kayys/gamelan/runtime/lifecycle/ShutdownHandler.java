package tech.kayys.gamelan.runtime.lifecycle;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles graceful shutdown of the runtime.
 */
@ApplicationScoped
public class ShutdownHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ShutdownHandler.class);

    void onStop(@Observes ShutdownEvent ev) {
        LOG.info("The Gamelan Runtime is stopping...");

        // TODO: Add logic to:
        // 1. Stop accepting new tasks (if applicable via a drain flag)
        // 2. Wait for graceful termination of active gRPC calls (Quarkus should handle
        // most)
        // 3. Close manually managed resources

        LOG.info("Shutdown sequence initiated.");
    }
}
