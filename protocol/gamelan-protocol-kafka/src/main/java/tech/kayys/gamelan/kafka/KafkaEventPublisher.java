package tech.kayys.gamelan.kafka;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Publishes domain events to Kafka
 */
@Priority(APPLICATION + 10)
@ApplicationScoped
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventPublisher.class);

    @Inject
    @Channel("workflow-events")
    Emitter<WorkflowEventMessage> eventEmitter;

    @Override
    public void publish(String eventType, Object payload, WorkflowContext workflowContext) {
        LOG.debug("Publishing direct event to Kafka: {}", eventType);
        WorkflowEventMessage message = new WorkflowEventMessage(
                java.util.UUID.randomUUID().toString(),
                workflowContext.runId().value(),
                workflowContext.tenantId().value(),
                eventType,
                java.time.Instant.now(),
                (payload instanceof Map) ? (Map<String, Object>) payload : Map.of("data", payload));

        eventEmitter.send(message);
    }

    @Override
    public void publishSystem(String eventType, Object payload) {
        LOG.debug("Publishing system event to Kafka: {}", eventType);
        WorkflowEventMessage message = new WorkflowEventMessage(
                java.util.UUID.randomUUID().toString(),
                "system",
                "system",
                eventType,
                java.time.Instant.now(),
                (payload instanceof Map) ? (Map<String, Object>) payload : Map.of("data", payload));

        eventEmitter.send(message);
    }

    @Override
    public Uni<Void> publish(List<ExecutionEvent> events) {
        LOG.debug("Publishing {} events to Kafka", events.size());

        return Uni.join().all(
                events.stream()
                        .map(this::publishEvent)
                        .toList())
                .andFailFast()
                .replaceWithVoid()
                .onFailure().invoke(throwable -> LOG.error("Failed to publish events to Kafka", throwable));
    }

    private Uni<Void> publishEvent(ExecutionEvent event) {
        WorkflowEventMessage message = new WorkflowEventMessage(
                event.eventId(),
                event.runId().value(),
                extractTenantId(event),
                event.eventType(),
                event.occurredAt(),
                serializeEvent(event));

        return Uni.createFrom().completionStage(
                eventEmitter.send(message));
    }

    private String extractTenantId(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent wse) {
            return wse.tenantId().value();
        }
        return "system";
    }

    private Map<String, Object> serializeEvent(ExecutionEvent event) {
        // Serialize event to map for Kafka
        Map<String, Object> data = new HashMap<>();
        data.put("eventType", event.eventType());
        data.put("eventId", event.eventId());
        data.put("runId", event.runId().value());
        data.put("occurredAt", event.occurredAt().toString());

        // Add event-specific data
        if (event instanceof NodeCompletedEvent nce) {
            data.put("nodeId", nce.nodeId().value());
            data.put("attempt", nce.attempt());
            data.put("output", nce.output());
        } else if (event instanceof NodeFailedEvent nfe) {
            data.put("nodeId", nfe.nodeId().value());
            data.put("attempt", nfe.attempt());
            data.put("error", Map.of(
                    "code", nfe.error().code(),
                    "message", nfe.error().message()));
        }

        return data;
    }

    @Override
    public Uni<Void> publishRetry(WorkflowRunId runId, NodeId nodeId) {
        ExecutionEvent event = new GenericExecutionEvent(
                runId,
                "RetryScheduled",
                "Node retry scheduled",
                java.time.Instant.now(),
                java.util.Map.of("nodeId", nodeId.value()));
        return publish(List.of(event));
    }
}