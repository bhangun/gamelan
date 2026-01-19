package tech.kayys.gamelan.kafka;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Publishes workflow status updates to Kafka
 */
@ApplicationScoped
public class KafkaStatusPublisher {

        private static final Logger LOG = LoggerFactory.getLogger(KafkaStatusPublisher.class);

        @Inject
        @Channel("workflow-status")
        Emitter<Record<String, StatusUpdateMessage>> statusEmitter;

        /**
         * Publish status update
         */
        public Uni<Void> publishStatusUpdate(
                        WorkflowRunId runId,
                        RunStatus status,
                        String message) {
                LOG.debug("Publishing status update for run {}", runId.value());
                StatusUpdateMessage update = new StatusUpdateMessage(
                                runId.value(),
                                status.name(),
                                message,
                                Instant.now());

                return Uni.createFrom().completionStage(
                                statusEmitter.send(Record.of(runId.value(), update))).onFailure()
                                .invoke(throwable -> LOG.error("Failed to publish status update", throwable));
        }
}
