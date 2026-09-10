package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;

/**
 * In-memory Task Queue for standalone/local mode.
 * Active by default; replaced by durable queue implementations in distributed deployments.
 */
@ApplicationScoped
@DefaultBean
public class InMemoryTaskQueue implements TaskQueue {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryTaskQueue.class);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration DEFAULT_LEASE_SCAN_INTERVAL = Duration.ofSeconds(1);

    private final UnicastProcessor<QueuedTask> processor = UnicastProcessor.create();
    private final AtomicLong counter = new AtomicLong(0);
    private final AtomicLong leaseCounter = new AtomicLong(0);
    private final Map<String, InFlightTask> inFlightTasks = new LinkedHashMap<>();
    private final Duration leaseDurationOverride;
    private ScheduledExecutorService leaseReaper;

    @ConfigProperty(name = "gamelan.task-queue.in-memory.lease-duration", defaultValue = "30s")
    Duration leaseDuration = DEFAULT_LEASE_DURATION;

    @ConfigProperty(name = "gamelan.task-queue.in-memory.lease-scan-interval", defaultValue = "1s")
    Duration leaseScanInterval = DEFAULT_LEASE_SCAN_INTERVAL;

    public InMemoryTaskQueue() {
        this(null);
    }

    InMemoryTaskQueue(Duration leaseDurationOverride) {
        this.leaseDurationOverride = leaseDurationOverride;
    }

    @PostConstruct
    void startLeaseReaper() {
        Duration interval = positiveDuration(leaseScanInterval, DEFAULT_LEASE_SCAN_INTERVAL);
        leaseReaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gamelan-in-memory-task-lease-reaper");
            thread.setDaemon(true);
            return thread;
        });
        leaseReaper.scheduleAtFixedRate(
                () -> expireLeases(Instant.now()),
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopLeaseReaper() {
        if (leaseReaper != null) {
            leaseReaper.shutdownNow();
        }
    }

    @Override
    public Uni<Void> enqueue(NodeExecutionTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        String messageId = String.valueOf(counter.incrementAndGet());
        Instant now = Instant.now();
        NodeExecutionTask deliveryTask = TaskQueueMetadata.deliveryTask(task, now);
        LOG.debug("Enqueuing task in-memory: {} (ID: {})", task.nodeId().value(), messageId);
        emit(delivery(messageId, deliveryTask, now));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Multi<QueuedTask> consume() {
        return processor;
    }

    @Override
    public Uni<Void> acknowledge(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            synchronized (inFlightTasks) {
                inFlightTasks.remove(messageId.trim());
            }
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> acknowledge(QueuedTask queuedTask) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        synchronized (inFlightTasks) {
            InFlightTask current = inFlightTasks.get(queuedTask.messageId());
            if (current != null && current.leaseId().equals(queuedTask.leaseId())) {
                inFlightTasks.remove(queuedTask.messageId());
            }
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> renewLease(QueuedTask queuedTask, Duration leaseDuration) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        Instant now = Instant.now();
        boolean renewed;
        synchronized (inFlightTasks) {
            InFlightTask current = inFlightTasks.get(queuedTask.messageId());
            if (current == null
                    || !current.leaseId().equals(queuedTask.leaseId())
                    || !current.leaseExpiresAt().isAfter(now)) {
                renewed = false;
            } else {
                Duration effectiveLeaseDuration = positiveDuration(leaseDuration, effectiveLeaseDuration());
                inFlightTasks.put(
                        queuedTask.messageId(),
                        current.withLeaseExpiresAt(now.plus(effectiveLeaseDuration)));
                renewed = true;
            }
        }
        return Uni.createFrom().item(renewed);
    }

    @Override
    public Uni<TaskQueue.QueueStats> stats() {
        return Uni.createFrom().item(() -> stats(Instant.now()));
    }

    TaskQueue.QueueStats stats(Instant now) {
        Instant reference = now != null ? now : Instant.now();
        long leased = 0;
        long expired = 0;
        synchronized (inFlightTasks) {
            for (InFlightTask task : inFlightTasks.values()) {
                if (task.leaseExpiresAt().isAfter(reference)) {
                    leased++;
                } else {
                    expired++;
                }
            }
        }
        return TaskQueue.QueueStats.known(leased + expired, 0, leased, expired, 0);
    }

    int expireLeases(Instant now) {
        Instant reference = now != null ? now : Instant.now();
        List<QueuedTask> redeliveries = new ArrayList<>();
        synchronized (inFlightTasks) {
            for (Map.Entry<String, InFlightTask> entry : new ArrayList<>(inFlightTasks.entrySet())) {
                InFlightTask current = entry.getValue();
                if (!current.leaseExpiresAt().isAfter(reference)) {
                    QueuedTask expired = current.queuedTask(entry.getKey());
                    NodeExecutionTask redeliveredTask = TaskQueueMetadata.redeliveredTask(expired);
                    redeliveries.add(delivery(entry.getKey(), redeliveredTask, reference));
                }
            }
        }
        redeliveries.forEach(this::emit);
        return redeliveries.size();
    }

    int inFlightCount() {
        synchronized (inFlightTasks) {
            return inFlightTasks.size();
        }
    }

    private QueuedTask delivery(String messageId, NodeExecutionTask task, Instant now) {
        String leaseId = messageId + ":" + leaseCounter.incrementAndGet();
        Instant leaseExpiresAt = now.plus(effectiveLeaseDuration());
        InFlightTask inFlight = new InFlightTask(task, leaseId, leaseExpiresAt);
        synchronized (inFlightTasks) {
            inFlightTasks.put(messageId, inFlight);
        }
        return inFlight.queuedTask(messageId);
    }

    private void emit(QueuedTask queuedTask) {
        processor.onNext(queuedTask);
    }

    private Duration effectiveLeaseDuration() {
        return positiveDuration(
                leaseDurationOverride != null ? leaseDurationOverride : leaseDuration,
                DEFAULT_LEASE_DURATION);
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative()
                ? value
                : fallback;
    }

    private record InFlightTask(
            NodeExecutionTask task,
            String leaseId,
            Instant leaseExpiresAt) {

        private QueuedTask queuedTask(String messageId) {
            return new QueuedTask(messageId, task, leaseId, leaseExpiresAt);
        }

        private InFlightTask withLeaseExpiresAt(Instant newLeaseExpiresAt) {
            return new InFlightTask(task, leaseId, newLeaseExpiresAt);
        }
    }
}
