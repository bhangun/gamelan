package tech.kayys.gamelan.kafka;

import java.time.Instant;
import java.util.Map;

/**
 * Task message for Kafka
 */
public record TaskMessage(
        String taskId,
        String runId,
        String nodeId,
        int attempt,
        String executionToken,
        Map<String, Object> context,
        String targetExecutor,
        Instant scheduledAt) {
}
