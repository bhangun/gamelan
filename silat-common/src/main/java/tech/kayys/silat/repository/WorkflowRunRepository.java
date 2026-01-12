package tech.kayys.silat.repository;

import java.util.List;

import io.smallrye.mutiny.Uni;

import tech.kayys.silat.model.CallbackRegistration;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.RunStatus;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.model.WorkflowRun;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.WorkflowRunSnapshot;

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

    <T> Uni<T> withLock(WorkflowRunId runId, java.util.function.Function<WorkflowRun, Uni<T>> action);

    Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId);

    Uni<List<WorkflowRun>> query(
            TenantId tenantId,
            WorkflowDefinitionId definitionId,
            RunStatus status,
            int page,
            int size);

    Uni<Long> countActiveRuns(TenantId tenantId);

    Uni<Void> storeToken(ExecutionToken token);

    Uni<Boolean> validateToken(ExecutionToken token);

    Uni<Void> storeCallback(CallbackRegistration callback);

    Uni<Boolean> validateCallback(WorkflowRunId runId, String token);
}
