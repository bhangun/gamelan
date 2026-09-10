package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface RetryManager {
    Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay);

    default Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Duration delay) {
        return scheduleRetry(runId, nodeId, delay);
    }

    default Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt,
            Duration delay) {
        return scheduleRetry(runId, tenantId, nodeId, delay);
    }
}
