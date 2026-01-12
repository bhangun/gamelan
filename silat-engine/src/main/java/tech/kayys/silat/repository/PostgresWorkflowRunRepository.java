package tech.kayys.silat.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

import tech.kayys.silat.domain.WorkflowRunEntity;
import tech.kayys.silat.execution.NodeExecutionSnapshot;
import tech.kayys.silat.model.CallbackRegistration;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.RunStatus;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.model.WorkflowRun;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.WorkflowRunSnapshot;

@ApplicationScoped
public class PostgresWorkflowRunRepository implements WorkflowRunRepository,
                PanacheRepositoryBase<WorkflowRunEntity, String> {

        private static final Logger LOG = LoggerFactory.getLogger(PostgresWorkflowRunRepository.class);

        @Inject
        ObjectMapper objectMapper;

        @Inject
        Pool pgPool;

        @Override
        public Uni<WorkflowRun> persist(WorkflowRun run) {
                WorkflowRunEntity entity = toEntity(run);
                return persist(entity)
                                .map(saved -> run)
                                .onFailure()
                                .invoke(throwable -> LOG.error("Failed to persist workflow run: {}",
                                                run.getId().value(), throwable));
        }

        @Override
        public Uni<WorkflowRun> update(WorkflowRun run) {
                // Since we are updating, we should merge or update the existing entity
                WorkflowRunEntity entity = toEntity(run);
                // Using getEntityManager().merge(entity) for update if detached, or just
                // persist if managed?
                // Panache persist acts as persist.
                // For update, we usually fetch and update or use merge.
                return Panache.withTransaction(() -> getSession().flatMap(session -> session.merge(entity)))
                                .map(merged -> run);
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
                return Panache.withTransaction(() -> find("runId", runId.value())
                                .withLock(LockModeType.PESSIMISTIC_WRITE)
                                .firstResult()
                                .flatMap(entity -> {
                                        if (entity == null) {
                                                return Uni.createFrom().failure(new NoSuchElementException(
                                                                "WorkflowRun not found: " + runId.value()));
                                        }
                                        // We must map it to domain, executing action, and then likely forcing update if
                                        // changed?
                                        // The action returns Uni<T>. If action modifies the domain object and calls
                                        // repository.update, that's fine.
                                        // But here we just pass the domain object.
                                        WorkflowRun run = toDomain(entity);
                                        if (run == null) {
                                                // Fallback if toDomain returns null (as per current stub)
                                                return Uni.createFrom().failure(new IllegalStateException(
                                                                "Failed to map entity to domain"));
                                        }
                                        return action.apply(run);
                                }));
        }

        @Override
        public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
                // Minimal implementation
                return findById(runId, tenantId).map(run -> {
                        if (run == null)
                                return null;
                        return run.createSnapshot();
                });
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id) {
                return find("runId", id.value())
                                .firstResult()
                                .map(entity -> entity != null ? toDomain(entity) : null);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
                return find("runId = ?1 and tenantId = ?2", id.value(), tenantId.value())
                                .firstResult()
                                .map(entity -> entity != null ? toDomain(entity) : null);
        }

        @Override
        public Uni<List<WorkflowRun>> query(
                        TenantId tenantId,
                        WorkflowDefinitionId definitionId,
                        RunStatus status,
                        int page,
                        int size) {

                StringBuilder query = new StringBuilder("tenantId = ?1");
                List<Object> params = new ArrayList<>();
                params.add(tenantId.value());

                if (definitionId != null) {
                        query.append(" and definitionId = ?").append(params.size() + 1);
                        params.add(definitionId.value());
                }

                if (status != null) {
                        query.append(" and status = ?").append(params.size() + 1);
                        params.add(status);
                }

                return find(query.toString(), params.toArray())
                                .page(page, size)
                                .list()
                                .map(entities -> entities.stream()
                                                .map(this::toDomain)
                                                .toList());
        }

        @Override
        public Uni<Long> countActiveRuns(TenantId tenantId) {
                return count("tenantId = ?1 and status in ('RUNNING', 'PENDING', 'SUSPENDED')",
                                tenantId.value());
        }

        @Override
        public Uni<Void> storeToken(ExecutionToken token) {
                String sql = """
                                INSERT INTO execution_tokens
                                (token_value, run_id, node_id, attempt, expires_at, created_at)
                                VALUES ($1, $2, $3, $4, $5, $6)
                                ON CONFLICT (token_value) DO NOTHING
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.of(
                                                token.value(),
                                                token.runId().value(),
                                                token.nodeId().value(),
                                                token.attempt(),
                                                token.expiresAt(),
                                                Instant.now()))
                                .replaceWithVoid();
        }

        @Override
        public Uni<Boolean> validateToken(ExecutionToken token) {
                String sql = """
                                SELECT EXISTS(
                                    SELECT 1 FROM execution_tokens
                                    WHERE token_value = $1
                                    AND run_id = $2
                                    AND node_id = $3
                                    AND expires_at > $4
                                )
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.of(
                                                token.value(),
                                                token.runId().value(),
                                                token.nodeId().value(),
                                                Instant.now()))
                                .map(RowSet::iterator)
                                .map(iter -> iter.hasNext() && iter.next().getBoolean(0));
        }

        @Override
        public Uni<Void> storeCallback(CallbackRegistration callback) {
                String sql = """
                                INSERT INTO workflow_callbacks
                                (callback_token, run_id, node_id, callback_url, expires_at, created_at)
                                VALUES ($1, $2, $3, $4, $5, $6)
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.of(
                                                callback.callbackToken(),
                                                callback.runId().value(),
                                                callback.nodeId().value(),
                                                callback.callbackUrl(),
                                                callback.expiresAt(),
                                                Instant.now()))
                                .replaceWithVoid();
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
                String sql = """
                                SELECT EXISTS(
                                    SELECT 1 FROM workflow_callbacks
                                    WHERE run_id = $1
                                    AND callback_token = $2
                                    AND expires_at > $3
                                )
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.of(runId.value(), token, Instant.now()))
                                .map(RowSet::iterator)
                                .map(iter -> iter.hasNext() && iter.next().getBoolean(0));
        }

        // Mapping methods
        private WorkflowRunEntity toEntity(WorkflowRun run) {
                WorkflowRunEntity entity = new WorkflowRunEntity();
                entity.setRunId(run.getId().value());
                entity.setTenantId(run.getTenantId().value());
                entity.setDefinitionId(run.getDefinitionId().value());
                entity.setStatus(run.getStatus());
                entity.setContextVariables(run.getContext().getVariables());
                entity.setCreatedAt(run.getCreatedAt());
                entity.setVersion(run.getVersion());

                // Convert node executions
                Map<String, NodeExecutionSnapshot> nodeSnapshots = new HashMap<>();
                run.getAllNodeExecutions().forEach((nodeId, exec) -> {
                        nodeSnapshots.put(nodeId.value(), new NodeExecutionSnapshot(
                                        nodeId.value(),
                                        exec.getStatus().name(),
                                        exec.getAttempt(),
                                        null, // simplified
                                        null,
                                        exec.getOutput(),
                                        null));
                });
                entity.setNodeExecutions(nodeSnapshots);

                return entity;
        }

        private WorkflowRun toDomain(WorkflowRunEntity entity) {
                // This is simplified - full implementation would reconstruct from events
                // For now, return null and rely on event sourcing
                return null;
        }
}