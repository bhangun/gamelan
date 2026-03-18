package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface RetryManager {
    Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay);
}
