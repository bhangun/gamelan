package tech.kayys.gamelan.kafka;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import io.smallrye.reactive.messaging.kafka.Record;

/**
 * Produces task assignments to Kafka for executors
 */
@ApplicationScoped
public class KafkaTaskProducer {

        private static final Logger LOG = LoggerFactory.getLogger(KafkaTaskProducer.class);

        @Inject
        @Channel("workflow-tasks")
        Emitter<Record<String, TaskMessage>> taskEmitter;

        /**
         * Send task to executor via Kafka
         */
        public Uni<Void> sendTask(NodeExecutionTask task, String targetExecutor) {
                LOG.debug("Sending task to Kafka: run={}, node={}, executor={}",
                                task.runId().value(), task.nodeId().value(), targetExecutor);

                TaskMessage message = new TaskMessage(
                                task.taskId(),
                                task.runId().value(),
                                tenantId(task),
                                task.nodeId().value(),
                                task.attempt(),
                                task.token().value(),
                                task.context(),
                                targetExecutor,
                                Instant.now());

                return Uni.createFrom().completionStage(
                                taskEmitter.send(Record.of(task.runId().value(), message))).onFailure()
                                .invoke(throwable -> LOG.error("Failed to send task to Kafka", throwable));
        }

        private String tenantId(NodeExecutionTask task) {
                if (task == null) {
                        return null;
                }
                if (task.context() != null) {
                        Object value = task.context().get(NodeExecutionTask.TENANT_ID_KEY);
                        if (value != null) {
                                String tenantId = String.valueOf(value);
                                if (!tenantId.isBlank()) {
                                        return tenantId;
                                }
                        }
                }
                return task.token() != null && task.token().tenantId() != null
                                ? task.token().tenantId().value()
                                : null;
        }
}
