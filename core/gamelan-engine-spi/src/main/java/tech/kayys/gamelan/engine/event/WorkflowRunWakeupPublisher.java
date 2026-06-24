package tech.kayys.gamelan.engine.event;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Publishes level-triggered workflow run wake-ups to the orchestration driver.
 *
 * Implementations can be in-memory, event-bus backed, outbox backed, or routed
 * to an external broker depending on runtime profile.
 */
public interface WorkflowRunWakeupPublisher {

    Uni<Void> publish(WorkflowRunUpdateEvent event);

    default WorkflowRunWakeupPublisherDiagnostics diagnostics() {
        return WorkflowRunWakeupPublisherDiagnostics.unavailable(getClass().getName());
    }

    default Uni<Void> publish(WorkflowRunId runId, TenantId tenantId, String reason) {
        return publish(WorkflowRunUpdateEvent.of(runId, tenantId, reason));
    }
}
