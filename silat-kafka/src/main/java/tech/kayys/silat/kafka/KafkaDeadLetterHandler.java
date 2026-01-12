package tech.kayys.silat.kafka;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Handles failed messages
 */
@ApplicationScoped
public class KafkaDeadLetterHandler {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaDeadLetterHandler.class);

    @Inject
    @Channel("workflow-dlq")
    Emitter<DeadLetterMessage> dlqEmitter;

    /**
     * Send failed message to DLQ
     */
    public Uni<Void> sendToDeadLetter(
            String originalTopic,
            String originalKey,
            Object originalValue,
            Throwable error) {

        LOG.warn("Sending message to DLQ: topic={}, error={}",
                originalTopic, error.getMessage());

        DeadLetterMessage dlqMessage = new DeadLetterMessage(
                originalTopic,
                originalKey,
                originalValue.toString(),
                error.getMessage(),
                Instant.now());

        return Uni.createFrom().completionStage(
                dlqEmitter.send(dlqMessage));
    }

    /**
     * Process dead letter messages
     */
    @Incoming("workflow-dlq")
    @Blocking
    public void processDeadLetter(DeadLetterMessage message) {
        LOG.error("Dead letter message: topic={}, key={}, error={}", 
            message.originalTopic(), message.originalKey(), message.errorMessage());
        
        // Store in database for analysis
        // Alert operations team
        // Attempt retry with exponential backoff
    }
}
