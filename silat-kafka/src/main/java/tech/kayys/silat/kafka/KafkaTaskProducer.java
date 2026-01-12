package tech.kayys.silat.kafka;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.NodeExecutionTask;
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
                generateTaskId(task),
                task.runId().value(),
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

    private String generateTaskId(NodeExecutionTask task) {
        return String.format("%s:%s:%d",
                task.runId().value(),
                task.nodeId().value(),
                task.attempt());
    }
}
