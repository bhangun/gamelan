package tech.kayys.gamelan.sdk.executor.core;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

import jakarta.enterprise.context.ApplicationScoped;

import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service to handle executor registration and heartbeating
 */
@ApplicationScoped
public class ExecutorRegistrationService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorRegistrationService.class);

    // Default topics for local implementation
    private static final String TOPIC_REGISTER = "gamelan.executor.register";
    private static final String TOPIC_UNREGISTER = "gamelan.executor.unregister";
    private static final String TOPIC_HEARTBEAT = "gamelan.executor.heartbeat";

    // Heartbeat interval
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    @Inject
    Vertx vertx;

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private long heartbeatTimerId = -1;

    /**
     * Register the executor with the engine
     */
    public Uni<Void> register(ExecutorConfig config, String executorType, String executorId) {
        if (registered.get()) {
            return Uni.createFrom().voidItem();
        }

        ExecutorInfo info = new ExecutorInfo(
                executorId,
                executorType,
                config.communicationType(),
                "local", // Default for now, should be discovered or configured
                Duration.ofSeconds(30), // Default timeout
                Map.of("version", "1.0.0") // Default metadata
        );

        // Convert to JsonObject for Vert.x event bus
        JsonObject json = JsonObject.mapFrom(info);

        return vertx.eventBus().request(TOPIC_REGISTER, json)
                .invoke(() -> {
                    LOG.info("Registered executor {} with engine", executorId);
                    registered.set(true);
                    startHeartbeat(executorId);
                })
                .onFailure().invoke(err -> LOG.error("Failed to register executor {}", executorId, err))
                .replaceWithVoid();
    }

    /**
     * Unregister the executor
     */
    public Uni<Void> unregister(String executorId) {
        if (!registered.get()) {
            return Uni.createFrom().voidItem();
        }

        stopHeartbeat();

        return vertx.eventBus().request(TOPIC_UNREGISTER, executorId)
                .invoke(() -> {
                    LOG.info("Unregistered executor {}", executorId);
                    registered.set(false);
                })
                .onFailure().invoke(err -> LOG.error("Failed to unregister executor {}", executorId, err))
                .replaceWithVoid();
    }

    private void startHeartbeat(String executorId) {
        heartbeatTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL.toMillis(), id -> {
            vertx.eventBus().publish(TOPIC_HEARTBEAT, executorId);
            LOG.trace("Sent heartbeat for executor {}", executorId);
        });
    }

    private void stopHeartbeat() {
        if (heartbeatTimerId != -1) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }
    }
}
