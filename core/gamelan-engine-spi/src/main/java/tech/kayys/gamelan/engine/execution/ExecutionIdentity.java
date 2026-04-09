package tech.kayys.gamelan.engine.execution;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Immutable identity of a workflow execution.
 * Stable for the entire lifetime of a WorkflowRun.
 */
public record ExecutionIdentity(
        WorkflowRunId runId,
        TenantId tenantId) {

    public ExecutionIdentity {
        java.util.Objects.requireNonNull(runId, "runId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
    }
}
