package tech.kayys.gamelan.kafka;

import java.time.Instant;

/**
 * Status update message for Kafka
 */
record StatusUpdateMessage(
        String runId,
        String status,
        String message,
        Instant timestamp) {
}
