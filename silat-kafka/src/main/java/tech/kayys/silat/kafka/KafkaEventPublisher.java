package tech.kayys.silat.kafka;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.api.event.EventPublisher;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;
import tech.kayys.silat.model.event.ExecutionEvent;
import tech.kayys.silat.model.event.NodeCompletedEvent;
import tech.kayys.silat.model.event.NodeFailedEvent;
import tech.kayys.silat.model.event.WorkflowStartedEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.reactive.messaging.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ============================================================================
 * KAFKA INTEGRATION
 * ============================================================================
 *
 * Kafka-based event streaming for:
 * - Domain event publishing
 * - Task distribution to executors
 * - Result collection from executors
 * - Workflow status updates
 *
 * Topics:
 * - workflow.events       - Domain events (event sourcing)
 * - workflow.tasks        - Task assignments for executors
 * - workflow.results      - Task results from executors
 * - workflow.status       - Status updates for real-time monitoring
 */

// ==================== EVENT PUBLISHER ====================

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