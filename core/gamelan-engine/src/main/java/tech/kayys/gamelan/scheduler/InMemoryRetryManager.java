package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
@DefaultBean
public class InMemoryRetryManager implements RetryManager {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryRetryManager.class);
    
    private final Map<String, Instant> retryQueue = new ConcurrentHashMap<>();

    @Inject
    EventPublisher eventPublisher;

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay) {
        Instant executeAt = Instant.now().plus(delay);
        String entry = runId.value() + ":" + nodeId.value();
        
        LOG.info("Scheduling in-memory retry run={}, node={} at {}", 
                runId.value(), nodeId.value(), executeAt);
        
        retryQueue.put(entry, executeAt);
        return Uni.createFrom().voidItem();
    }

    @Scheduled(every = "5s")
    void processRetryQueue() {
        Instant now = Instant.now();
        retryQueue.entrySet().stream()
            .filter(e -> e.getValue().isBefore(now))
            .forEach(e -> {
                String entry = e.getKey();
                if (retryQueue.remove(entry) != null) {
                    handleRetryEntry(entry);
                }
            });
    }

    private void handleRetryEntry(String entry) {
        String[] parts = entry.split(":");
        if (parts.length != 2) return;

        WorkflowRunId runId = WorkflowRunId.of(parts[0]);
        NodeId nodeId = NodeId.of(parts[1]);

        LOG.info("Retrying node {} for run {} (in-memory)", nodeId.value(), runId.value());
        eventPublisher.publishRetry(runId, nodeId)
                .subscribe().with(v -> {}, err -> LOG.error("Retry publish failed", err));
    }
}
