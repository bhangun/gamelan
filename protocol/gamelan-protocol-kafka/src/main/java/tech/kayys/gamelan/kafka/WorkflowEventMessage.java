package tech.kayys.gamelan.kafka;

import java.time.Instant;
import java.util.Map;

/**
 * Event message for Kafka
 */
record WorkflowEventMessage(
        String eventId,
        String runId,
        String tenantId,
        String eventType,
        Instant occurredAt,
        Map<String, Object> eventData) {
}