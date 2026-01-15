package tech.kayys.silat.registry;

import io.quarkus.runtime.Startup;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for local executor registration events via Vert.x EventBus.
 */
@Startup
@ApplicationScoped
public class LocalRegistrationListener {

        private static final Logger LOG = LoggerFactory.getLogger(LocalRegistrationListener.class);
        private static final String TOPIC_REGISTER = "silat.executor.register";
        private static final String TOPIC_UNREGISTER = "silat.executor.unregister";
        private static final String TOPIC_HEARTBEAT = "silat.executor.heartbeat";

        @Inject
        EventBus eventBus;

        @Inject
        ExecutorRegistry executorRegistry;

        @jakarta.annotation.PostConstruct
        void init() {
                LOG.info("Initializing LocalRegistrationListener");

                eventBus.<io.vertx.core.json.JsonObject>consumer(TOPIC_REGISTER)
                                .handler(msg -> {
                                        tech.kayys.silat.model.ExecutorInfo info = msg.body()
                                                        .mapTo(tech.kayys.silat.model.ExecutorInfo.class);
                                        LOG.info("Received local registration request for: {}", info.executorId());
                                        executorRegistry.registerExecutor(info).subscribe().with(
                                                        v -> LOG.info("Local executor registered: {}",
                                                                        info.executorId()),
                                                        error -> LOG.error("Failed to register local executor", error));
                                });

                eventBus.<String>consumer(TOPIC_UNREGISTER)
                                .handler(msg -> {
                                        String executorId = msg.body();
                                        LOG.info("Received local unregistration request for: {}", executorId);
                                        executorRegistry.unregisterExecutor(executorId).subscribe().with(
                                                        v -> LOG.info("Local executor unregistered: {}", executorId),
                                                        error -> LOG.error("Failed to unregister local executor",
                                                                        error));
                                });

                eventBus.<String>consumer(TOPIC_HEARTBEAT)
                                .handler(msg -> {
                                        String executorId = msg.body();
                                        LOG.trace("Received local heartbeat from: {}", executorId);
                                        executorRegistry.heartbeat(executorId).subscribe().with(
                                                        v -> LOG.trace("Heartbeat processed for: {}", executorId),
                                                        error -> LOG.warn("Failed to process heartbeat for: {}",
                                                                        executorId, error));
                                });
        }
}
