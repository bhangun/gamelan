package tech.kayys.gamelan.engine.execution;

import java.util.Map;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;

public record ExecutionRequest(
        WorkflowDefinitionId definitionId,
        TenantId tenantId,
        Map<String, Object> input,
        ExecutionMode mode, // SYNC | ASYNC | DRY_RUN
        String correlationId) {
}