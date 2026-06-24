package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
@DefaultBean
public class InMemoryRetryManager implements RetryManager {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryRetryManager.class);

    private final Map<String, Instant> retryQueue = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Inject
    EventPublisher eventPublisher;

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay) {
        return scheduleRetry(runId, null, nodeId, delay);
    }

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Duration delay) {
        return scheduleRetry(runId, tenantId, nodeId, null, delay);
    }

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt,
            Duration delay) {
        return scheduleRetry(runId, tenantId, nodeId, Integer.valueOf(attempt), delay);
    }

    private Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Integer attempt,
            Duration delay) {
        Duration safeDelay = delay != null && !delay.isNegative() ? delay : Duration.ZERO;
        Instant executeAt = Instant.now().plus(safeDelay);
        String entry = attempt != null
                ? RetryEntries.encode(runId, tenantId, nodeId, attempt)
                : RetryEntries.encode(runId, tenantId, nodeId);

        LOG.info("Scheduling in-memory retry run={}, tenant={}, node={}, attempt={} at {}",
                runId.value(), tenantValue(tenantId), nodeId.value(), attemptValue(attempt), executeAt);

        retryQueue.merge(entry, executeAt, InMemoryRetryManager::earliest);
        return Uni.createFrom().voidItem();
    }

    @Scheduled(every = "{gamelan.retry.scan-interval:5s}")
    void processRetryQueue() {
        Instant now = Instant.now();
        retryQueue.entrySet().stream()
                .filter(e -> !e.getValue().isAfter(now))
                .forEach(e -> handleRetryEntry(e.getKey(), e.getValue()));
    }

    private void handleRetryEntry(String entry, Instant executeAt) {
        if (!inFlight.add(entry)) {
            return;
        }

        RetryEntries.decode(entry).ifPresentOrElse(decoded -> publishRetry(entry, executeAt, decoded),
                () -> {
                    retryQueue.remove(entry);
                    inFlight.remove(entry);
                    LOG.warn("Discarding invalid in-memory retry entry: {}", entry);
                });
    }

    private void publishRetry(String entry, Instant executeAt, RetryEntries.Entry decoded) {
        WorkflowRunId runId = decoded.runId();
        TenantId tenantId = decoded.tenantId();
        NodeId nodeId = decoded.nodeId();
        Integer attempt = decoded.attempt();

        LOG.info("Retrying node {} for run {} tenant={} attempt={} (in-memory)", nodeId.value(), runId.value(),
                tenantValue(tenantId), attemptValue(attempt));
        publishRetryEvent(runId, tenantId, nodeId, attempt)
                .subscribe().with(
                        ignored -> {
                            retryQueue.remove(entry, executeAt);
                            inFlight.remove(entry);
                        },
                        error -> {
                            inFlight.remove(entry);
                            LOG.warn("Retry publish failed; keeping retry entry for redelivery error={}: {}",
                                    error.getClass().getName(),
                                    error.getMessage());
                            LOG.debug("Retry publish failure details", error);
                        });
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static String tenantValue(TenantId tenantId) {
        return tenantId != null ? tenantId.value() : "";
    }

    private Uni<Void> publishRetryEvent(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Integer attempt) {
        return attempt != null
                ? eventPublisher.publishRetry(runId, tenantId, nodeId, attempt)
                : eventPublisher.publishRetry(runId, tenantId, nodeId);
    }

    private static String attemptValue(Integer attempt) {
        return attempt != null ? attempt.toString() : "";
    }
}
