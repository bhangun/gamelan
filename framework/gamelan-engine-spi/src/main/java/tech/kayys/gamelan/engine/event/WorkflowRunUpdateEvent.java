package tech.kayys.gamelan.engine.event;

import java.util.Objects;
import java.util.Optional;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Internal event-bus payload for level-triggered run wakeups.
 */
public record WorkflowRunUpdateEvent(
        String runId,
        String tenantId,
        String reason) {

    public static final String ADDRESS = "gamelan.runs.v1.updated";

    public WorkflowRunUpdateEvent {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("WorkflowRunId cannot be blank");
        }
        runId = runId.trim();
        tenantId = tenantId != null && !tenantId.isBlank() ? tenantId.trim() : null;
        reason = reason != null && !reason.isBlank() ? reason.trim() : "updated";
    }

    public static WorkflowRunUpdateEvent of(
            WorkflowRunId runId,
            TenantId tenantId,
            String reason) {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        return new WorkflowRunUpdateEvent(
                runId.value(),
                tenantId != null ? tenantId.value() : null,
                reason);
    }

    public WorkflowRunId workflowRunId() {
        return WorkflowRunId.of(runId);
    }

    public Optional<TenantId> tenant() {
        return tenantId != null && !tenantId.isBlank()
                ? Optional.of(TenantId.of(tenantId))
                : Optional.empty();
    }
}
