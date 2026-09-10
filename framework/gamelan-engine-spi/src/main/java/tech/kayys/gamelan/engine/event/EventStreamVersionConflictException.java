package tech.kayys.gamelan.engine.event;

import java.util.Objects;
import java.util.Optional;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Raised when an event stream append loses the optimistic-lock race.
 */
public class EventStreamVersionConflictException extends IllegalStateException {

    private final WorkflowRunId runId;
    private final TenantId tenantId;
    private final long expectedVersion;

    public EventStreamVersionConflictException(
            WorkflowRunId runId,
            TenantId tenantId,
            long expectedVersion) {
        super("Event stream version conflict for run %s tenant %s: expected %d"
                .formatted(
                        Objects.requireNonNull(runId, "runId").value(),
                        tenantId != null ? tenantId.value() : "system",
                        expectedVersion));
        this.runId = runId;
        this.tenantId = tenantId;
        this.expectedVersion = expectedVersion;
    }

    public WorkflowRunId runId() {
        return runId;
    }

    public Optional<TenantId> tenantId() {
        return Optional.ofNullable(tenantId);
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
