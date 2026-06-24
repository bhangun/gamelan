package tech.kayys.gamelan.engine.execution;

import java.util.Objects;
import java.util.Optional;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Raised when an execution-history append is suppressed by event-id uniqueness.
 */
public class ExecutionHistoryAppendConflictException extends IllegalStateException {

    private final String eventId;
    private final WorkflowRunId runId;
    private final TenantId tenantId;

    public ExecutionHistoryAppendConflictException(
            String eventId,
            WorkflowRunId runId,
            TenantId tenantId) {
        super("Execution history event append conflict for event %s run %s tenant %s"
                .formatted(
                        Objects.requireNonNull(eventId, "eventId"),
                        Objects.requireNonNull(runId, "runId").value(),
                        tenantId != null ? tenantId.value() : "system"));
        this.eventId = eventId;
        this.runId = runId;
        this.tenantId = tenantId;
    }

    public String eventId() {
        return eventId;
    }

    public WorkflowRunId runId() {
        return runId;
    }

    public Optional<TenantId> tenantId() {
        return Optional.ofNullable(tenantId);
    }
}
