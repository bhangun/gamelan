package tech.kayys.gamelan.kafka;

import java.time.Instant;

/**
 * Dead letter message
 */
public record DeadLetterMessage(
        String originalTopic,
        String originalKey,
        String originalValue,
        String errorMessage,
        Instant timestamp) {
}
