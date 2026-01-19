package tech.kayys.gamelan.registry;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Default Event Publisher - Publishes domain events (stub implementation)
 */
@Alternative
@Priority(APPLICATION)
@ApplicationScoped
public class EventPublisher implements tech.kayys.gamelan.engine.event.EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(EventPublisher.class);
    private static final String EVENT_TOPIC = "workflow.events";

    @Override
    public void publish(String eventType, Object payload, WorkflowContext workflowContext) {
        LOG.debug("Publishing event: {} to topic: {}", eventType, EVENT_TOPIC);
    }

    @Override
    public void publishSystem(String eventType, Object payload) {
        LOG.debug("Publishing system event: {} to topic: {}", eventType, EVENT_TOPIC);
    }

    @Override
    public Uni<Void> publish(List<ExecutionEvent> events) {
        LOG.debug("Publishing {} events to topic: {}", events.size(), EVENT_TOPIC);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> publishRetry(
            WorkflowRunId runId,
            NodeId nodeId) {
        ExecutionEvent event = new tech.kayys.gamelan.engine.event.GenericExecutionEvent(
                runId,
                "RetryScheduled",
                "Node retry scheduled",
                java.time.Instant.now(),
                java.util.Map.of("nodeId", nodeId.value()));
        return publish(List.of(event));
    }
}