package tech.kayys.silat.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Status notification service
 */
@ApplicationScoped
public class StatusNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(StatusNotificationService.class);

    public void notifyStatusChange(String runId, String status, String message) {
        LOG.info("Status change notification: run={}, status={}", runId, status);

        // Send to WebSocket clients
        // Send to SSE streams
        // Send to webhooks
    }
}
