package tech.kayys.silat.kafka;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.NodeExecutionResult;
import io.smallrye.reactive.messaging.kafka.Record;

/**
 * Produces task results to Kafka (executor side)
 */
@ApplicationScoped
public class KafkaResultProducer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaResultProducer.class);

    @Inject
    @Channel("workflow-results")
    Emitter<Record<String, TaskResultMessage>> resultEmitter;

    /**
     * Send task result to engine via Kafka
     */
    public Uni<Void> sendResult(NodeExecutionResult result) {
        LOG.debug("Sending result to Kafka: run={}, node={}, status={}",
                result.runId().value(), result.nodeId().value(), result.status());

        TaskResultMessage message = new TaskResultMessage(
                result.runId().value(),
                result.nodeId().value(),
                result.attempt(),
                result.status().name(),
                result.output(),
                result.error() != null ? Map.of(
                        "code", result.error().code(),
                        "message", result.error().message()) : null,
                result.executionToken().value(),
                Instant.now());

        return Uni.createFrom().completionStage(
                resultEmitter.send(Record.of(result.runId().value(), message))).onFailure()
                .invoke(throwable -> LOG.error("Failed to send result to Kafka", throwable));
    }
}
