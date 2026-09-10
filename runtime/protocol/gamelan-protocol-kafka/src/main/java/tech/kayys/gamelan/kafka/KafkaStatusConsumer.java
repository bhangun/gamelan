package tech.kayys.gamelan.kafka;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumes status updates (for monitoring/dashboards)
 */
@ApplicationScoped
public class KafkaStatusConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStatusConsumer.class);

    @Inject
    StatusNotificationService notificationService;

    /**
     * Consume status updates
     */
    @Incoming("workflow-status")
    public void consumeStatusUpdate(StatusUpdateMessage update) {
        LOG.debug("Received status update: run={}, status={}",
                update.runId(), update.status());

        // Forward to notification service (WebSocket, SSE, etc.)
        notificationService.notifyStatusChange(
                update.runId(),
                update.status(),
                update.message());
    }
}
