package tech.kayys.silat.kafka;

import java.time.Instant;
import java.util.Map;

/**
 * Task result message for Kafka
 */
record TaskResultMessage(
        String runId,
        String nodeId,
        int attempt,
        String status,
        Map<String, Object> output,
        Map<String, String> error,
        String executionToken,
        Instant completedAt) {
}
