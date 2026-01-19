package tech.kayys.gamelan.kafka;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Consumes tasks from Kafka (executor side)
 * This would typically be in the executor SDK
 */
@ApplicationScoped
public class KafkaTaskConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTaskConsumer.class);

    @Inject
    ExecutorTaskHandler taskHandler;

    /**
     * Consume tasks from Kafka
     */
    @Incoming("workflow-tasks")
    @Blocking
    public void consumeTask(TaskMessage task) {
        LOG.info("Received task from Kafka: {}", task.taskId());

        try {
            // Convert to domain object
            NodeExecutionTask executionTask = new NodeExecutionTask(
                    WorkflowRunId.of(task.runId()),
                    NodeId.of(task.nodeId()),
                    task.attempt(),
                    new ExecutionToken(
                            task.executionToken(),
                            WorkflowRunId.of(task.runId()),
                            NodeId.of(task.nodeId()),
                            task.attempt(),
                            Instant.now().plusSeconds(3600)),
                    task.context(), null);

            // Hand off to executor
            taskHandler.executeTask(executionTask)
                    .subscribe().with(
                            result -> LOG.info("Task completed: {}", task.taskId()),
                            error -> LOG.error("Task failed: {}", task.taskId(), error));

        } catch (Exception e) {
            LOG.error("Failed to process task: {}", task.taskId(), e);
        }
    }
}
