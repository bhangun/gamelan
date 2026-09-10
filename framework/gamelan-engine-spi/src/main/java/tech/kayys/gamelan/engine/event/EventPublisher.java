package tech.kayys.gamelan.engine.event;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Event Publisher API - Publishes domain events
 */
public interface EventPublisher {

        void publish(
                        String eventType,
                        Object payload,
                        WorkflowContext workflowContext);

        void publishSystem(
                        String eventType,
                        Object payload);

        Uni<Void> publish(List<ExecutionEvent> events);

        default EventPublisherDiagnostics diagnostics() {
                return EventPublisherDiagnostics.unavailable(getClass().getName());
        }

        Uni<Void> publishRetry(
                        WorkflowRunId runId,
                        NodeId nodeId);

        default Uni<Void> publishRetry(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId) {
                return publishRetry(runId, nodeId);
        }

        default Uni<Void> publishRetry(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId,
                        int attempt) {
                return publishRetry(runId, tenantId, nodeId);
        }
}
