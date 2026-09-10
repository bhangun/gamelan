package tech.kayys.gamelan.kafka;

import java.time.Instant;
import java.util.Map;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionResults;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.engine.tenant.TenantId;
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
                        NodeExecutionResult executionResult = NodeExecutionResults.fromExternal(
                                        result.runId(),
                                        result.nodeId(),
                                        result.attempt(),
                                        result.status(),
                                        result.output(),
                                        toErrorInfo(result.error()),
                                        result.executionToken(),
                                        result.tenantId(),
                                        Instant.now().plusSeconds(3600));

                        // Submit through the external executor boundary so execution tokens are validated.
                        TenantId tenantId = tenantId(result.tenantId());
                        Uni<NodeResultHandlingOutcome> completion = tenantId != null
                                        ? runManager.onNodeExecutionCompletedWithOutcome(
                                                        executionResult,
                                                        tenantId,
                                                        result.executionToken())
                                        : runManager.onNodeExecutionCompletedWithOutcome(
                                                        executionResult,
                                                        result.executionToken());

                        completion.subscribe().with(
                                                        outcome -> LOG.info(
                                                                        "Result processed: run={}, node={}, acceptance={}, duplicate={}, runUpdated={}",
                                                                        result.runId(),
                                                                        result.nodeId(),
                                                                        outcome.acceptance(),
                                                                        outcome.duplicate(),
                                                                        outcome.runUpdated()),
                                                        error -> LOG.error("Failed to process result", error));

                } catch (Exception e) {
                        LOG.error("Failed to consume result", e);
                }
        }

        private ErrorInfo toErrorInfo(Map<String, String> error) {
                if (error == null) {
                        return null;
                }
                return new ErrorInfo(
                                error.get("code"),
                                error.get("message"),
                                "",
                                Map.of());
        }

        private TenantId tenantId(String tenantId) {
                if (tenantId == null || tenantId.isBlank()) {
                        return null;
                }
                return TenantId.of(tenantId);
        }
}
