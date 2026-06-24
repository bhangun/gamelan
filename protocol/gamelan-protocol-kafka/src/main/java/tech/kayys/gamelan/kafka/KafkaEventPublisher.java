package tech.kayys.gamelan.kafka;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Publishes workflow domain events to Kafka for distributed runtime profiles.
 */
@Priority(APPLICATION + 10)
@ApplicationScoped
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventPublisher.class);

    @Inject
    @Channel("workflow-events")
    Emitter<WorkflowEventMessage> eventEmitter;

    private final AtomicLong directEventsPublished = new AtomicLong();
    private final AtomicLong systemEventsPublished = new AtomicLong();
    private final AtomicLong batchEventsPublished = new AtomicLong();
    private final AtomicLong retryEventsPublished = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private volatile String lastFailure;
    private volatile Instant lastFailureAt;

    @Override
    public void publish(String eventType, Object payload, WorkflowContext workflowContext) {
        LOG.debug("Publishing direct event to Kafka: {}", eventType);
        WorkflowEventMessage message = new WorkflowEventMessage(
                java.util.UUID.randomUUID().toString(),
                workflowContext.runId().value(),
                workflowContext.tenantId().value(),
                eventType,
                java.time.Instant.now(),
                payloadMap(payload));

        eventEmitter.send(message)
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        directEventsPublished.incrementAndGet();
                    } else {
                        recordFailure(error);
                    }
                });
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
                payloadMap(payload));

        eventEmitter.send(message)
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        systemEventsPublished.incrementAndGet();
                    } else {
                        recordFailure(error);
                    }
                });
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
                .onFailure().invoke(throwable -> {
                    recordFailure(throwable);
                    LOG.error("Failed to publish events to Kafka", throwable);
                });
    }

    private Uni<Void> publishEvent(ExecutionEvent event) {
        WorkflowEventMessage message = new WorkflowEventMessage(
                event.eventId(),
                event.runId().value(),
                extractTenantId(event),
                event.eventType(),
                event.occurredAt(),
                serializeEvent(event));

        return Uni.createFrom().completionStage(eventEmitter.send(message))
                .invoke(() -> batchEventsPublished.incrementAndGet())
                .onFailure().invoke(this::recordFailure);
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

    private static Map<String, Object> payloadMap(Object payload) {
        Map<String, Object> data = new HashMap<>();
        if (payload instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null) {
                    data.put(String.valueOf(key), value);
                }
            });
            return data;
        }
        data.put("data", payload);
        return data;
    }

    @Override
    public Uni<Void> publishRetry(WorkflowRunId runId, NodeId nodeId) {
        return publishRetry(runId, null, nodeId);
    }

    @Override
    public Uni<Void> publishRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        return publishRetry(runId, tenantId, nodeId, 0);
    }

    @Override
    public Uni<Void> publishRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeId", nodeId.value());
        if (tenantId != null) {
            metadata.put("tenantId", tenantId.value());
        }
        if (attempt > 0) {
            metadata.put("attempt", attempt);
        }
        ExecutionEvent event = new GenericExecutionEvent(
                runId,
                "RetryScheduled",
                "Node retry scheduled",
                java.time.Instant.now(),
                Map.copyOf(metadata));
        return publish(List.of(event))
                .invoke(() -> retryEventsPublished.incrementAndGet());
    }

    @Override
    public EventPublisherDiagnostics diagnostics() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("directEventsPublished", directEventsPublished.get());
        counters.put("systemEventsPublished", systemEventsPublished.get());
        counters.put("batchEventsPublished", batchEventsPublished.get());
        counters.put("retryEventsPublished", retryEventsPublished.get());
        counters.put("publishFailures", publishFailures.get());
        return EventPublisherDiagnostics.available(
                getClass().getName(),
                counters,
                lastFailure,
                lastFailureAt);
    }

    private void recordFailure(Throwable error) {
        publishFailures.incrementAndGet();
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
}
