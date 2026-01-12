package tech.kayys.silat.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.execution.ExecutionHistory;
import tech.kayys.silat.execution.ExternalSignal;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.model.*;

import java.util.List;
import java.util.Map;

/**
 * Workflow Run Manager - Authoritative Orchestrator Interface
 * 
 * Guarantees:
 * - No loss of existing functionality
 * - Deterministic state transitions
 * - Idempotent external interactions
 * - Safe for distributed / multi-instance execution
 */
public interface WorkflowRunManager {

        // ==================== LIFECYCLE OPERATIONS ====================

        Uni<WorkflowRun> createRun(CreateRunRequest request, TenantId tenantId);

        Uni<WorkflowRun> startRun(WorkflowRunId runId, TenantId tenantId);

        Uni<WorkflowRun> suspendRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        String reason,
                        NodeId waitingOnNodeId);

        Uni<WorkflowRun> resumeRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Map<String, Object> resumeData);

        Uni<Void> cancelRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        String reason);

        Uni<WorkflowRun> completeRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Map<String, Object> outputs);

        Uni<WorkflowRun> failRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        ErrorInfo error);

        // ==================== NODE EXECUTION FEEDBACK ====================

        /**
         * Internal node completion path (trusted execution plane)
         */
        Uni<Void> handleNodeResult(
                        WorkflowRunId runId,
                        NodeExecutionResult result);

        /**
         * Runtime signal (pause, resume, retry, custom)
         */
        Uni<Void> signal(
                        WorkflowRunId runId,
                        Signal signal);

        // ==================== QUERY OPERATIONS ====================

        Uni<WorkflowRun> getRun(
                        WorkflowRunId runId,
                        TenantId tenantId);

        Uni<WorkflowRunSnapshot> getSnapshot(
                        WorkflowRunId runId,
                        TenantId tenantId);

        Uni<ExecutionHistory> getExecutionHistory(
                        WorkflowRunId runId,
                        TenantId tenantId);

        Uni<List<WorkflowRun>> queryRuns(
                        TenantId tenantId,
                        WorkflowDefinitionId definitionId,
                        RunStatus status,
                        int page,
                        int size);

        Uni<Long> getActiveRunsCount(TenantId tenantId);

        /**
         * Dry-run validation only, no mutation
         */
        Uni<ValidationResult> validateTransition(
                        WorkflowRunId runId,
                        RunStatus targetStatus);

        // ==================== TOKEN MANAGEMENT ====================

        /**
         * Creates a short-lived execution token for execution-plane usage
         */
        Uni<ExecutionToken> createExecutionToken(
                        WorkflowRunId runId,
                        NodeId nodeId,
                        int attempt);

        // ==================== EXTERNAL INTEGRATION ====================

        /**
         * External executor callback (idempotent, signed)
         */
        Uni<Void> onNodeExecutionCompleted(
                        NodeExecutionResult result,
                        String executorSignature);

        Uni<Void> onExternalSignal(
                        WorkflowRunId runId,
                        ExternalSignal signal,
                        String callbackToken);

        Uni<CallbackRegistration> registerCallback(
                        WorkflowRunId runId,
                        NodeId nodeId,
                        CallbackConfig config);
}
