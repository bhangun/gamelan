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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

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

        Map<String, String> metadata = executorMetadata(config);
        ExecutorInfo info = new ExecutorInfo(
                executorId,
                executorType,
                config.communicationType(),
                "local", // Default for now, should be discovered or configured
                Duration.ofSeconds(30), // Default timeout
                metadata);

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

    private static Map<String, String> executorMetadata(ExecutorConfig config) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");

        CommunicationType communicationType = config.communicationType();
        if (communicationType == CommunicationType.LOCAL) {
            metadata.put(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "local");
            metadata.put(ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "none");
        } else if (communicationType == CommunicationType.GRPC
                || communicationType == CommunicationType.REST
                || communicationType == CommunicationType.KAFKA) {
            metadata.put(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "remote,distributed");
        }

        return metadata;
    }
}
