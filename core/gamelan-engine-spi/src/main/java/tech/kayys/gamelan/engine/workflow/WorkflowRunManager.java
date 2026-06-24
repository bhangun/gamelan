package tech.kayys.gamelan.engine.workflow;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeDispatchReservation;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.engine.node.NodeExecutionResults;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.signal.ExternalSignal;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;

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

        /**
         * Primary entry point — tenantId is embedded in the request (set at API boundary).
         */
        Uni<WorkflowRun> createRun(CreateRunRequest request);

        /**
         * @deprecated Prefer {@link #createRun(CreateRunRequest)} with tenantId embedded in request.
         */
        @Deprecated
        default Uni<WorkflowRun> createRun(CreateRunRequest request, TenantId tenantId) {
                request.setTenantId(tenantId);
                return createRun(request);
        }

        Uni<WorkflowRun> startRun(WorkflowRunId runId, TenantId tenantId);

        Uni<WorkflowRun> suspendRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        String reason,
                        NodeId waitingOnNodeId);

        Uni<WorkflowRun> resumeRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Map<String, Object> resumeData,
                        String humanTaskId);

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

        Uni<Void> completeCompensation(
                        WorkflowRunId runId,
                        TenantId tenantId);

        Uni<Void> failCompensation(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        ErrorInfo error);

        // ==================== NODE DISPATCH STATE ====================

        /**
         * Atomically reserve a ready node attempt before the orchestration layer dispatches
         * it to an executor.
         */
        Uni<NodeDispatchReservation> reserveNodeForDispatch(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId);

        /**
         * Fail a previously reserved node attempt after dispatch or executor selection fails.
         */
        Uni<Void> failNodeExecution(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId,
                        int attempt,
                        ErrorInfo error,
                        String wakeupReason);

        // ==================== NODE EXECUTION FEEDBACK ====================

        /**
         * Internal node completion path (trusted execution plane)
         */
        Uni<Void> handleNodeResult(
                        WorkflowRunId runId,
                        NodeExecutionResult result);

        default Uni<NodeResultHandlingOutcome> handleNodeResultWithOutcome(
                        WorkflowRunId runId,
                        NodeExecutionResult result) {
                return handleNodeResult(runId, result)
                                .onItem().transform(ignored -> defaultAcceptedOutcome(runId, null, result));
        }

        default Uni<Void> handleNodeResult(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result) {
                return handleNodeResult(runId, result);
        }

        default Uni<NodeResultHandlingOutcome> handleNodeResultWithOutcome(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result) {
                return handleNodeResult(runId, tenantId, result)
                                .onItem().transform(ignored -> defaultAcceptedOutcome(runId, tenantId, result));
        }

        /**
         * Runtime signal (pause, resume, retry, custom)
         */
        Uni<Void> signal(
                        WorkflowRunId runId,
                        Signal signal);

        default Uni<Void> signal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Signal signal) {
                return signal(runId, signal);
        }

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

        default Uni<ExecutionToken> createExecutionToken(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId,
                        int attempt) {
                return createExecutionToken(runId, nodeId, attempt);
        }

        // ==================== EXTERNAL INTEGRATION ====================

        /**
         * External executor callback (idempotent, signed)
         */
        Uni<Void> onNodeExecutionCompleted(
                        NodeExecutionResult result,
                        String executorSignature);

        default Uni<NodeResultHandlingOutcome> onNodeExecutionCompletedWithOutcome(
                        NodeExecutionResult result,
                        String executorSignature) {
                return onNodeExecutionCompleted(result, executorSignature)
                                .onItem().transform(ignored -> defaultAcceptedOutcome(
                                                result != null ? result.runId() : null,
                                                null,
                                                result));
        }

        default Uni<Void> onNodeExecutionCompleted(
                        NodeExecutionResult result,
                        TenantId tenantId,
                        String executorSignature) {
                return onNodeExecutionCompleted(result, executorSignature);
        }

        default Uni<NodeResultHandlingOutcome> onNodeExecutionCompletedWithOutcome(
                        NodeExecutionResult result,
                        TenantId tenantId,
                        String executorSignature) {
                return onNodeExecutionCompleted(result, tenantId, executorSignature)
                                .onItem().transform(ignored -> defaultAcceptedOutcome(
                                                result != null ? result.runId() : null,
                                                tenantId,
                                                result));
        }

        Uni<Void> onExternalSignal(
                        WorkflowRunId runId,
                        ExternalSignal signal,
                        String callbackToken);

        default Uni<Void> onExternalSignal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        ExternalSignal signal,
                        String callbackToken) {
                return onExternalSignal(runId, signal, callbackToken);
        }

        private static NodeResultHandlingOutcome defaultAcceptedOutcome(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result) {
                return new NodeResultHandlingOutcome(
                                runId,
                                tenantId,
                                result.nodeId(),
                                result.attempt(),
                                NodeExecutionResults.Acceptance.ACCEPT,
                                true,
                                true,
                                true,
                                false);
        }

        Uni<CallbackRegistration> registerCallback(
                        WorkflowRunId runId,
                        NodeId nodeId,
                        CallbackConfig config);

        default Uni<CallbackRegistration> registerCallback(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId,
                        CallbackConfig config) {
                return registerCallback(runId, nodeId, config);
        }
}
