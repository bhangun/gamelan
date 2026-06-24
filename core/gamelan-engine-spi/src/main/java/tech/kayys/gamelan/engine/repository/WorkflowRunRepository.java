package tech.kayys.gamelan.engine.repository;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

/**
 * ============================================================================
 * PERSISTENCE LAYER - Event Sourcing + Snapshots
 * ============================================================================
 *
 * Architecture:
 * - Event Store: Immutable append-only log of all events
 * - Snapshot Store: Materialized views for fast querying
 * - Optimistic Locking: Version-based concurrency control
 *
 * Pattern: CQRS (Command Query Responsibility Segregation)
 * - Commands write to event store
 * - Queries read from snapshot store
 * - Async projection from events to snapshots
 */

// ==================== REPOSITORY INTERFACE ====================

public interface WorkflowRunRepository {

    Uni<WorkflowRun> persist(WorkflowRun run);

    Uni<WorkflowRun> update(WorkflowRun run);

    Uni<WorkflowRun> findById(WorkflowRunId id);

    Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId);

    <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action);

    default <T> Uni<T> withLock(WorkflowRunId runId, TenantId tenantId, Function<WorkflowRun, Uni<T>> action) {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        Objects.requireNonNull(tenantId, "TenantId cannot be null");
        Objects.requireNonNull(action, "Lock action cannot be null");

        return withLock(runId, run -> {
            if (run == null || !tenantId.equals(run.getTenantId())) {
                return Uni.createFrom().failure(
                        new NoSuchElementException("WorkflowRun not found: " + runId.value()));
            }
            return action.apply(run);
        });
    }

    Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId);

    Uni<List<WorkflowRun>> query(
            TenantId tenantId,
            WorkflowDefinitionId definitionId,
            RunStatus status,
            int page,
            int size);

    /**
     * Paged running-run scan used by recovery sweepers.
     *
     * Implementations should prefer indexed status scans and stable paging. The
     * default keeps legacy/custom repositories source-compatible but means they
     * opt out of automatic recovery until implemented.
     */
    default Uni<List<WorkflowRun>> queryActiveRunsForRecovery(int page, int size) {
        return Uni.createFrom().item(List.of());
    }

    /**
     * Cursor-based active-run scan used by recovery sweepers.
     *
     * Implementations should prefer deterministic keyset scans over offset paging.
     * The default delegates to {@link #queryActiveRunsForRecovery(int, int)} so
     * existing repository implementations remain source-compatible.
     */
    default Uni<WorkflowRunRecoveryPage> scanActiveRunsForRecovery(WorkflowRunRecoveryCursor cursor, int size) {
        WorkflowRunRecoveryCursor safeCursor = cursor != null ? cursor : WorkflowRunRecoveryCursor.start();
        int safeSize = size > 0 ? size : 100;
        return queryActiveRunsForRecovery(safeCursor.page(), safeSize)
                .map(runs -> WorkflowRunRecoveryPage.offset(runs, safeCursor, safeSize));
    }

    Uni<Long> countActiveRuns(TenantId tenantId);

    Uni<Void> storeToken(ExecutionToken token);

    Uni<Boolean> validateToken(ExecutionToken token);

    Uni<Void> storeCallback(CallbackRegistration callback);

    Uni<Boolean> validateCallback(WorkflowRunId runId, String token);

    default Uni<Boolean> validateCallback(WorkflowRunId runId, TenantId tenantId, String token) {
        return validateCallback(runId, token);
    }

    /**
     * Surgical update of a single context variable (JSONB performance)
     */
    Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value);

    /**
     * Surgical update of a node execution status/output
     */
    Uni<Void> updateNodeExecution(WorkflowRunId runId, tech.kayys.gamelan.engine.node.NodeId nodeId, tech.kayys.gamelan.engine.node.NodeExecutionSnapshot snapshot);
}
