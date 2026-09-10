package tech.kayys.gamelan.engine.impl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.quarkus.arc.DefaultBean;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisherDiagnostics;

/**
 * Default local wake-up publisher used by Quarkus/Vert.x runtime profiles.
 */
@ApplicationScoped
@DefaultBean
public class VertxWorkflowRunWakeupPublisher implements WorkflowRunWakeupPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VertxWorkflowRunWakeupPublisher.class);
    private static final int DEFAULT_DRAIN_BATCH_SIZE = 256;
    private static final int DEFAULT_DELIVERY_PARALLELISM = 32;

    @Inject
    io.vertx.mutiny.core.eventbus.EventBus eventBus;

    @Inject
    WorkflowRunWakeupOutbox outbox;

    @ConfigProperty(name = "gamelan.workflow.wakeup.drain-batch-size", defaultValue = "256")
    int drainBatchSize = DEFAULT_DRAIN_BATCH_SIZE;

    @ConfigProperty(name = "gamelan.workflow.wakeup.delivery-parallelism", defaultValue = "32")
    int deliveryParallelism = DEFAULT_DELIVERY_PARALLELISM;

    private final Set<String> inFlightWakeupIntents = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean drainInProgress = new AtomicBoolean();
    private final AtomicLong publishRequests = new AtomicLong();
    private final AtomicLong enqueueFailures = new AtomicLong();
    private final AtomicLong deliveredWakeups = new AtomicLong();
    private final AtomicLong deliveryFailures = new AtomicLong();
    private final AtomicLong drainRuns = new AtomicLong();
    private final AtomicLong drainSkipped = new AtomicLong();
    private final AtomicLong drainFailures = new AtomicLong();
    private final AtomicLong claimedWakeups = new AtomicLong();
    private volatile WorkflowRunWakeupOutbox fallbackOutbox;
    private volatile String lastFailure;
    private volatile Instant lastFailureAt;

    @Override
    public Uni<Void> publish(WorkflowRunUpdateEvent event) {
        Objects.requireNonNull(event, "WorkflowRunUpdateEvent cannot be null");
        publishRequests.incrementAndGet();
        return outbox().enqueue(event)
                .flatMap(this::deliver)
                .onFailure().invoke(error -> {
                    enqueueFailures.incrementAndGet();
                    recordLastFailure(error);
                    LOG.warn(
                            "Workflow run wake-up enqueue failed run={}, reason={}: {}",
                            event.runId(),
                            event.reason(),
                            error.getMessage());
                })
                .onFailure().recoverWithNull();
    }

    @Scheduled(every = "{gamelan.workflow.wakeup.retry-interval:5s}")
    void drainPendingWakeups() {
        if (!drainInProgress.compareAndSet(false, true)) {
            drainSkipped.incrementAndGet();
            LOG.debug("Skipping workflow run wake-up outbox drain because another drain is still active");
            return;
        }

        drainRuns.incrementAndGet();
        outbox().claimPending(effectiveDrainBatchSize())
                .invoke(intents -> claimedWakeups.addAndGet(intents.size()))
                .flatMap(this::deliverClaimedWakeups)
                .eventually(() -> {
                    drainInProgress.set(false);
                    return Uni.createFrom().voidItem();
                })
                .subscribe().with(
                        ignored -> {
                        },
                        error -> {
                            drainFailures.incrementAndGet();
                            recordLastFailure(error);
                            LOG.warn("Workflow run wake-up outbox drain failed: {}", error.getMessage());
                        });
    }

    public int pendingWakeupCount() {
        return outbox().pending(Integer.MAX_VALUE)
                .map(List::size)
                .await().indefinitely();
    }

    public List<PendingWakeupSnapshot> pendingWakeupSnapshots() {
        return outbox().pending(Integer.MAX_VALUE)
                .await().indefinitely()
                .stream()
                .map(intent -> new PendingWakeupSnapshot(
                        coalesceKey(intent.event()),
                        intent.event().runId(),
                        intent.event().tenantId(),
                        intent.event().reason(),
                        intent.attempts(),
                        intent.lastAttemptAt(),
                        intent.lastError(),
                        inFlightWakeupIntents.contains(intent.id())))
                .toList();
    }

    private Uni<Void> publishNow(WorkflowRunUpdateEvent event) {
        if (eventBus == null) {
            return Uni.createFrom().failure(new IllegalStateException("event bus unavailable"));
        }
        return Uni.createFrom().voidItem()
                .invoke(() -> eventBus.publish(WorkflowRunUpdateEvent.ADDRESS, JsonObject.mapFrom(event)));
    }

    private Uni<Void> deliverClaimedWakeups(List<WorkflowRunWakeupIntent> intents) {
        if (intents.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        int parallelism = Math.min(effectiveDeliveryParallelism(), intents.size());
        return Multi.createFrom().iterable(intents)
                .onItem().transformToUni(this::deliver)
                .merge(parallelism)
                .collect().asList()
                .replaceWithVoid();
    }

    private Uni<Void> deliver(WorkflowRunWakeupIntent intent) {
        if (!inFlightWakeupIntents.add(intent.id())) {
            return Uni.createFrom().voidItem();
        }
        return publishNow(intent.event())
                .call(() -> outbox().markDelivered(intent.id(), intent.event()))
                .invoke(() -> deliveredWakeups.incrementAndGet())
                .onFailure().call(error -> outbox().markFailed(intent.id(), error)
                        .onFailure().invoke(markError -> LOG.warn(
                                "Workflow run wake-up failure mark failed intent={}, run={}: {}",
                                intent.id(),
                                intent.event().runId(),
                                markError.getMessage()))
                        .onFailure().recoverWithNull())
                .onFailure().invoke(error -> {
                    deliveryFailures.incrementAndGet();
                    recordLastFailure(error);
                    LOG.warn("Queued workflow run wake-up for retry run={}, reason={}, error={}: {}",
                            intent.event().runId(),
                            intent.event().reason(),
                            error.getClass().getName(),
                            error.getMessage());
                    LOG.debug("Workflow run wake-up publish failure details", error);
                })
                .eventually(() -> inFlightWakeupIntents.remove(intent.id()))
                .onFailure().recoverWithNull();
    }

    boolean drainInProgress() {
        return drainInProgress.get();
    }

    @Override
    public WorkflowRunWakeupPublisherDiagnostics diagnostics() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("publishRequests", publishRequests.get());
        counters.put("enqueueFailures", enqueueFailures.get());
        counters.put("deliveredWakeups", deliveredWakeups.get());
        counters.put("deliveryFailures", deliveryFailures.get());
        counters.put("drainRuns", drainRuns.get());
        counters.put("drainSkipped", drainSkipped.get());
        counters.put("drainFailures", drainFailures.get());
        counters.put("claimedWakeups", claimedWakeups.get());
        return WorkflowRunWakeupPublisherDiagnostics.available(
                getClass().getName(),
                drainInProgress.get(),
                inFlightWakeupIntents.size(),
                effectiveDrainBatchSize(),
                effectiveDeliveryParallelism(),
                counters,
                lastFailure,
                lastFailureAt);
    }

    private int effectiveDrainBatchSize() {
        return drainBatchSize > 0 ? drainBatchSize : DEFAULT_DRAIN_BATCH_SIZE;
    }

    private int effectiveDeliveryParallelism() {
        return deliveryParallelism > 0 ? deliveryParallelism : DEFAULT_DELIVERY_PARALLELISM;
    }

    private WorkflowRunWakeupOutbox outbox() {
        WorkflowRunWakeupOutbox configured = outbox;
        if (configured != null) {
            return configured;
        }
        WorkflowRunWakeupOutbox local = fallbackOutbox;
        if (local == null) {
            synchronized (this) {
                local = fallbackOutbox;
                if (local == null) {
                    local = new InMemoryWorkflowRunWakeupOutbox();
                    fallbackOutbox = local;
                }
            }
        }
        return local;
    }

    private void recordLastFailure(Throwable error) {
        lastFailure = errorSummary(error);
        lastFailureAt = Instant.now();
    }

    private static String errorSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    private String coalesceKey(WorkflowRunUpdateEvent event) {
        return (event.tenantId() != null ? event.tenantId() : "") + ":" + event.runId();
    }

    public record PendingWakeupSnapshot(
            String key,
            String runId,
            String tenantId,
            String reason,
            int attempts,
            Instant lastAttemptAt,
            String lastError,
            boolean inFlight) {
    }
}
