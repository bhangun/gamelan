package tech.kayys.silat.registry;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.model.event.ExecutionEvent;

/**
 * Event Publisher - Publishes domain events to Kafka
 */
@ApplicationScoped
public class EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(EventPublisher.class);
    private static final String EVENT_TOPIC = "workflow.events";

    public Uni<Void> publish(List<ExecutionEvent> events) {
        LOG.debug("Publishing {} events to topic: {}", events.size(), EVENT_TOPIC);

        // TODO: Implement Kafka publishing
        // This will be in the Kafka module

        return Uni.createFrom().voidItem();
    }

    public Uni<Void> publishRetry(
            tech.kayys.silat.model.WorkflowRunId runId,
            tech.kayys.silat.model.NodeId nodeId) {
        ExecutionEvent event = new tech.kayys.silat.model.event.GenericExecutionEvent(
                runId,
                "RetryScheduled",
                "Node retry scheduled",
                java.time.Instant.now(),
                java.util.Map.of("nodeId", nodeId.value()));
        return publish(List.of(event));
    }
}
