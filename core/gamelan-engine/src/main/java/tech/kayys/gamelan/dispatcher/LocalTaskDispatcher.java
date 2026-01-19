package tech.kayys.gamelan.dispatcher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

/**
 * Task dispatcher specialized for local (in-memory) communication via Vert.x
 * EventBus.
 */
@ApplicationScoped
public class LocalTaskDispatcher implements TaskDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTaskDispatcher.class);
    private static final String TOPIC_TASKS = "gamelan.tasks";

    @Inject
    EventBus eventBus;

    @Inject
    MeterRegistry meterRegistry;

    private Counter successCounter;
    private Counter failureCounter;
    private Timer dispatchTimer;

    @jakarta.annotation.PostConstruct
    void initMetrics() {
        this.successCounter = Counter.builder("gamelan.dispatcher.local.success")
                .description("Number of successful local dispatches")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("gamelan.dispatcher.local.failure")
                .description("Number of failed local dispatches")
                .register(meterRegistry);
        this.dispatchTimer = Timer.builder("gamelan.dispatcher.local.duration")
                .description("Local dispatch duration")
                .register(meterRegistry);
    }

    @Override
    public boolean supports(ExecutorInfo executor) {
        return executor != null && executor.communicationType() == CommunicationType.LOCAL;
    }

    @Override
    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo targetExecutor) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return Uni.createFrom().item(() -> {
            try {
                LOG.debug("Dispatching task {} locally via EventBus to executor {}", task.nodeId(),
                        targetExecutor.executorId());
                // In a real scenario you might want to target specific executors if you have
                // multiple local ones,
                // but for standalone single-instance mode, publishing to a shared topic is
                // often sufficient.
                // Or use a specific address like "gamelan.tasks.<executorId>"
                eventBus.publish(TOPIC_TASKS, io.vertx.core.json.JsonObject.mapFrom(task));
                successCounter.increment();
                sample.stop(dispatchTimer);
            } catch (Exception e) {
                failureCounter.increment();
                sample.stop(dispatchTimer);
                throw e;
            }
            return null;
        });
    }

    @Override
    public Uni<Boolean> isHealthy() {
        return Uni.createFrom().item(eventBus != null);
    }

    @Override
    public int getPriority() {
        // Local dispatch is fastest, highest priority
        return 10;
    }
}
