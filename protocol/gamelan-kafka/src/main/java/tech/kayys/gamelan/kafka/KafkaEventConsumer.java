package tech.kayys.gamelan.kafka;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumes domain events to build read models
 */
@ApplicationScoped
public class KafkaEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventConsumer.class);

    @Inject
    EventProjectionService projectionService;

    /**
     * Consume domain events for projections
     */
    @Incoming("workflow-events")
    @Blocking
    public void consumeEvent(WorkflowEventMessage event) {
        LOG.trace("Processing event: type={}, runId={}",
                event.eventType(), event.runId());

        try {
            // Project event to read models
            projectionService.project(event)
                    .subscribe().with(
                            v -> LOG.trace("Event projected: {}", event.eventId()),
                            error -> LOG.error("Failed to project event", error));

        } catch (Exception e) {
            LOG.error("Failed to consume event", e);
        }
    }
}
