package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.event.ExecutionEvent;

@ApplicationScoped
public class DefaultWorkflowScheduler implements WorkflowScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkflowScheduler.class);

    @Inject
    TaskQueue taskQueue;

    @Inject
    RetryManager retryManager;

    @Inject
    EventPublisher eventPublisher;

    @Override
    public Uni<Void> scheduleTask(NodeExecutionTask task) {
        LOG.info("Enqueuing task: run={}, node={}, attempt={}",
                task.runId().value(),
                task.nodeId().value(),
                task.attempt());

        return taskQueue.enqueue(task).replaceWithVoid();
    }

    @Override
    public Uni<Void> scheduleRetry(
            WorkflowRunId runId,
            NodeId nodeId,
            Duration delay) {
        return retryManager.scheduleRetry(runId, nodeId, delay);
    }

    @Override
    public Uni<Void> scheduleRetry(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            Duration delay) {
        return retryManager.scheduleRetry(runId, tenantId, nodeId, delay);
    }

    @Override
    public Uni<Void> scheduleRetry(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Duration delay) {
        return retryManager.scheduleRetry(runId, tenantId, nodeId, attempt, delay);
    }

    @Override
    public Uni<Void> cancelTasksForRun(WorkflowRunId runId) {
        // Distributed cancellation logic handled via status checks or stream poisoning
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> publishEvents(List<ExecutionEvent> events) {
        return events.isEmpty()
                ? Uni.createFrom().voidItem()
                : eventPublisher.publish(events);
    }

    @Override
    public Uni<Long> getScheduledTasksCount() {
        return Uni.createFrom().item(0L);
    }
}
