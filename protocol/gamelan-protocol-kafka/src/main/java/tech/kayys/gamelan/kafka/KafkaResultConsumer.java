package tech.kayys.gamelan.kafka;

import java.time.Instant;
import java.util.Map;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.DefaultNodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

/**
 * Consumes task results from Kafka (engine side)
 */
@ApplicationScoped
public class KafkaResultConsumer {

        private static final Logger LOG = LoggerFactory.getLogger(KafkaResultConsumer.class);

        @Inject
        WorkflowRunManager runManager;

        /**
         * Consume task results from Kafka
         */
        @Incoming("workflow-results")
        @Blocking
        public void consumeResult(TaskResultMessage result) {
                LOG.info("Received result from Kafka: run={}, node={}",
                                result.runId(), result.nodeId());

                try {
                        // Convert to domain object
                        NodeExecutionResult executionResult = new DefaultNodeExecutionResult(
                                        WorkflowRunId.of(result.runId()),
                                        NodeId.of(result.nodeId()),
                                        result.attempt(),
                                        NodeExecutionStatus.valueOf(result.status()),
                                        result.output(),
                                        result.error() != null ? new ErrorInfo(
                                                        result.error().get("code"),
                                                        result.error().get("message"),
                                                        "",
                                                        Map.of()) : null,
                                        new ExecutionToken(
                                                        result.executionToken(),
                                                        WorkflowRunId.of(result.runId()),
                                                        NodeId.of(result.nodeId()),
                                                        result.attempt(),
                                                        Instant.now().plusSeconds(3600)));

                        // Submit to run manager
                        runManager.handleNodeResult(
                                        WorkflowRunId.of(result.runId()),
                                        executionResult).subscribe().with(
                                                        v -> LOG.info("Result processed: run={}, node={}",
                                                                        result.runId(), result.nodeId()),
                                                        error -> LOG.error("Failed to process result", error));

                } catch (Exception e) {
                        LOG.error("Failed to consume result", e);
                }
        }
}