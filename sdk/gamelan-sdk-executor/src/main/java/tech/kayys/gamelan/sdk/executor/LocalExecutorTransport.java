package tech.kayys.gamelan.sdk.executor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.execution.NodeExecutionResult;
import tech.kayys.gamelan.execution.NodeExecutionTask;
import tech.kayys.gamelan.model.CommunicationType;
import tech.kayys.gamelan.model.ExecutorInfo;

/**
 * Transport implementation for local (same JVM) communication via Vert.x
 * EventBus
 */
@ApplicationScoped
public class LocalExecutorTransport implements ExecutorTransport {

    private static final Logger LOG = LoggerFactory.getLogger(LocalExecutorTransport.class);
    private static final String TOPIC_TASKS = "gamelan.tasks";
    private static final String TOPIC_RESULTS = "gamelan.results";
    private static final String TOPIC_REGISTER = "gamelan.executor.register";
    private static final String TOPIC_UNREGISTER = "gamelan.executor.unregister";
    private static final String TOPIC_HEARTBEAT = "gamelan.executor.heartbeat";

    private final java.util.Set<String> registeredExecutorIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject
    EventBus eventBus;

    @Override
    public tech.kayys.gamelan.model.CommunicationType getCommunicationType() {
        return tech.kayys.gamelan.model.CommunicationType.LOCAL;
    }

    @Override
    public Uni<Void> register(List<WorkflowExecutor> executors) {
        return Uni.createFrom().item(() -> {
            executors.forEach(executor -> {
                String executorId = "local-" + executor.getExecutorType();
                registeredExecutorIds.add(executorId);
                LOG.info("Registering local executor via EventBus: {} (id: {})", executor.getExecutorType(),
                        executorId);

                ExecutorInfo info = new ExecutorInfo(
                        executorId,
                        executor.getExecutorType(),
                        CommunicationType.LOCAL,
                        "local",
                        Duration.ofSeconds(30),
                        Map.of());

                eventBus.publish(TOPIC_REGISTER, io.vertx.core.json.JsonObject.mapFrom(info));
            });
            return null;
        });
    }

    @Override
    public Uni<Void> unregister() {
        return Uni.createFrom().item(() -> {
            LOG.info("Unregistering local executors via EventBus");
            registeredExecutorIds.clear();
            eventBus.publish(TOPIC_UNREGISTER, "all");
            return null;
        });
    }

    @Override
    public Multi<NodeExecutionTask> receiveTasks() {
        return eventBus.<io.vertx.core.json.JsonObject>consumer(TOPIC_TASKS)
                .toMulti()
                .map(msg -> msg.body().mapTo(NodeExecutionTask.class));
    }

    @Override
    public Uni<Void> sendResult(NodeExecutionResult result) {
        return Uni.createFrom().item(() -> {
            eventBus.publish(TOPIC_RESULTS, io.vertx.core.json.JsonObject.mapFrom(result));
            return null;
        });
    }

    @Override
    public Uni<Void> sendHeartbeat() {
        return Uni.createFrom().item(() -> {
            registeredExecutorIds.forEach(id -> eventBus.publish(TOPIC_HEARTBEAT, id));
            return null;
        });
    }
}
