package tech.kayys.gamelan.kafka;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
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
                                tenantId(result),
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
                                resultEmitter.send(Record.of(recordKey(message), message))).onFailure()
                                .invoke(throwable -> LOG.error("Failed to send result to Kafka", throwable));
        }

        private String recordKey(TaskResultMessage message) {
                return message.tenantId() != null && !message.tenantId().isBlank()
                                ? message.tenantId() + ":" + message.runId()
                                : message.runId();
        }

        private String tenantId(NodeExecutionResult result) {
                if (result == null) {
                        return null;
                }
                if (result.executionToken() != null && result.executionToken().tenantId() != null) {
                        return result.executionToken().tenantId().value();
                }
                if (result.getMetadata() == null) {
                        return null;
                }
                Object value = result.getMetadata().get(tech.kayys.gamelan.engine.node.NodeExecutionTask.TENANT_ID_KEY);
                if (value == null) {
                        value = result.getMetadata().get("tenantId");
                }
                if (value == null) {
                        return null;
                }
                String tenantId = String.valueOf(value);
                return tenantId.isBlank() ? null : tenantId;
        }
}
