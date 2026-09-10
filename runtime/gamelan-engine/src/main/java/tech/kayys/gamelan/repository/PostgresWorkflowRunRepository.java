package tech.kayys.gamelan.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

import tech.kayys.gamelan.domain.WorkflowRunEntity;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenHash;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryCursor;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryPage;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;

@ApplicationScoped
@jakarta.enterprise.inject.Alternative
@io.quarkus.arc.properties.IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
@io.quarkus.arc.properties.IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "postgres", enableIfMissing = true)
public class PostgresWorkflowRunRepository implements WorkflowRunRepository,
                PanacheRepositoryBase<WorkflowRunEntity, String> {

        private static final Logger LOG = LoggerFactory.getLogger(PostgresWorkflowRunRepository.class);

        @Inject
        ObjectMapper objectMapper;

        @Inject
        Pool pgPool;

        @Inject
        WorkflowDefinitionRepository definitionRepository;

        @Override
        public Uni<WorkflowRun> persist(WorkflowRun run) {
                WorkflowRunEntity entity = WorkflowRunEntityMapper.toEntity(run);
                return persist(entity)
                                .map(saved -> run)
                                .onFailure()
                                .invoke(throwable -> LOG.error("Failed to persist workflow run: {}",
                                                run.getId().value(), throwable));
        }

        @Override
        public Uni<WorkflowRun> update(WorkflowRun run) {
                // Since we are updating, we should merge or update the existing entity
                WorkflowRunEntity entity = WorkflowRunEntityMapper.toEntity(run);
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
                                        return toDomain(entity).flatMap(run -> action.apply(run));
                                }));
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, TenantId tenantId, Function<WorkflowRun, Uni<T>> action) {
                return Panache.withTransaction(() -> find("runId = ?1 and tenantId = ?2", runId.value(),
                                tenantId.value())
                                .withLock(LockModeType.PESSIMISTIC_WRITE)
                                .firstResult()
                                .flatMap(entity -> {
                                        if (entity == null) {
                                                return Uni.createFrom().failure(new NoSuchElementException(
                                                                "WorkflowRun not found: " + runId.value()));
                                        }
                                        return toDomain(entity).flatMap(run -> action.apply(run));
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
                                .flatMap(this::toDomain);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
                return find("runId = ?1 and tenantId = ?2", id.value(), tenantId.value())
                                .firstResult()
                                .flatMap(this::toDomain);
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
                                .flatMap(this::toDomains);
        }

        @Override
        public Uni<List<WorkflowRun>> queryActiveRunsForRecovery(int page, int size) {
                int safePage = Math.max(0, page);
                int safeSize = size > 0 ? size : 100;
                return find("status in ?1 order by runId", RunStatus.activeStatuses())
                                .page(safePage, safeSize)
                                .list()
                                .flatMap(this::toDomains);
        }

        @Override
        public Uni<WorkflowRunRecoveryPage> scanActiveRunsForRecovery(WorkflowRunRecoveryCursor cursor, int size) {
                WorkflowRunRecoveryCursor safeCursor = cursor != null ? cursor : WorkflowRunRecoveryCursor.start();
                int safeSize = size > 0 ? size : 100;
                Uni<List<WorkflowRunEntity>> page = safeCursor.hasAfterRunId()
                                ? find("status in ?1 and runId > ?2 order by runId",
                                                RunStatus.activeStatuses(),
                                                safeCursor.afterRunId())
                                                .page(0, safeSize + 1)
                                                .list()
                                : find("status in ?1 order by runId", RunStatus.activeStatuses())
                                                .page(0, safeSize + 1)
                                                .list();
                return page.flatMap(this::toDomains)
                                .map(runs -> WorkflowRunRecoveryPage.keyset(runs, safeSize));
        }

        @Override
        public Uni<Long> countActiveRuns(TenantId tenantId) {
                return count("tenantId = ?1 and status in ?2",
                                tenantId.value(),
                                RunStatus.activeStatuses());
        }

        @Override
        public Uni<Void> storeToken(ExecutionToken token) {
                String tenantId = token.tenantId() != null ? token.tenantId().value() : null;
                String sql = """
                                INSERT INTO execution_tokens
                                (token_hash, run_id, tenant_id, node_id, attempt, expires_at, created_at)
                                VALUES ($1, $2, $3, $4, $5, $6, $7)
                                ON CONFLICT (token_hash) DO NOTHING
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.tuple()
                                                .addValue(ExecutionTokenHash.sha256(token.value()))
                                                .addValue(token.runId().value())
                                                .addValue(tenantId)
                                                .addValue(token.nodeId().value())
                                                .addValue(token.attempt())
                                                .addValue(token.expiresAt())
                                                .addValue(Instant.now()))
                                .replaceWithVoid();
        }

        @Override
        public Uni<Boolean> validateToken(ExecutionToken token) {
                if (token == null) {
                        return Uni.createFrom().item(false);
                }

                String sql = """
                                SELECT EXISTS(
                                    SELECT 1 FROM execution_tokens
                                    WHERE token_hash = $1
                                    AND run_id = $2
                                    AND node_id = $3
                                    AND attempt = $4
                                    AND (tenant_id IS NULL OR tenant_id = $5)
                                    AND expires_at > $6
                                )
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.of(
                                                ExecutionTokenHash.sha256(token.value()),
                                                token.runId().value(),
                                                token.nodeId().value(),
                                                token.attempt(),
                                                token.tenantId() != null ? token.tenantId().value() : null,
                                                Instant.now()))
                                .map(RowSet::iterator)
                                .map(iter -> iter.hasNext() && iter.next().getBoolean(0));
        }

        @Override
        public Uni<Void> storeCallback(CallbackRegistration callback) {
                String tenantId = callback.tenantId() != null ? callback.tenantId().value() : null;
                String sql = """
                                INSERT INTO workflow_callbacks
                                (callback_token_hash, run_id, tenant_id, node_id, callback_url, expires_at, created_at)
                                VALUES ($1, $2, $3, $4, $5, $6, $7)
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.tuple()
                                                .addValue(BearerTokenHash.sha256(callback.callbackToken()))
                                                .addValue(callback.runId().value())
                                                .addValue(tenantId)
                                                .addValue(callback.nodeId().value())
                                                .addValue(callback.callbackUrl())
                                                .addValue(callback.expiresAt())
                                                .addValue(Instant.now()))
                                .replaceWithVoid();
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
                if (runId == null || token == null || token.isBlank()) {
                        return Uni.createFrom().item(false);
                }

                String sql = """
                                SELECT EXISTS(
                                    SELECT 1 FROM workflow_callbacks
                                    WHERE run_id = $1
                                    AND callback_token_hash = $2
                                    AND expires_at > $3
                                )
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.of(runId.value(), BearerTokenHash.sha256(token), Instant.now()))
                                .map(RowSet::iterator)
                                .map(iter -> iter.hasNext() && iter.next().getBoolean(0));
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, TenantId tenantId, String token) {
                if (runId == null || token == null || token.isBlank()) {
                        return Uni.createFrom().item(false);
                }

                String sql = """
                                SELECT EXISTS(
                                    SELECT 1 FROM workflow_callbacks
                                    WHERE run_id = $1
                                    AND callback_token_hash = $2
                                    AND (tenant_id IS NULL OR tenant_id = $3)
                                    AND expires_at > $4
                                )
                                """;

                return pgPool.preparedQuery(sql)
                                .execute(Tuple.of(
                                                runId.value(),
                                                BearerTokenHash.sha256(token),
                                                tenantId != null ? tenantId.value() : null,
                                                Instant.now()))
                                .map(RowSet::iterator)
                                .map(iter -> iter.hasNext() && iter.next().getBoolean(0));
        }

        @Override
        public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
                String sql = """
                                UPDATE workflow_runs
                                SET context_variables = jsonb_set(
                                    COALESCE(context_variables, '{}'::jsonb),
                                    ARRAY[$1],
                                    $2::jsonb,
                                    true
                                ),
                                last_updated_at = NOW()
                                WHERE run_id = $3
                                """;

                try {
                        String jsonValue = objectMapper.writeValueAsString(value);
                        return pgPool.preparedQuery(sql)
                                        .execute(Tuple.of(key, jsonValue, runId.value()))
                                        .replaceWithVoid();
                } catch (Exception e) {
                        return Uni.createFrom().failure(e);
                }
        }

        @Override
        public Uni<Void> updateNodeExecution(WorkflowRunId runId, tech.kayys.gamelan.engine.node.NodeId nodeId,
                        NodeExecutionSnapshot snapshot) {
                String sql = """
                                UPDATE workflow_runs
                                SET node_executions = jsonb_set(
                                    COALESCE(node_executions, '{}'::jsonb),
                                    ARRAY[$1],
                                    $2::jsonb,
                                    true
                                ),
                                last_updated_at = NOW()
                                WHERE run_id = $3
                                """;

                try {
                        String jsonValue = objectMapper.writeValueAsString(snapshot);
                        return pgPool.preparedQuery(sql)
                                        .execute(Tuple.of(nodeId.value(), jsonValue, runId.value()))
                                        .replaceWithVoid();
                } catch (Exception e) {
                        return Uni.createFrom().failure(e);
                }
        }

        private Uni<WorkflowRun> toDomain(WorkflowRunEntity entity) {
                if (entity == null) {
                        return Uni.createFrom().nullItem();
                }

                TenantId tenantId = TenantId.of(entity.getTenantId());
                WorkflowDefinitionId definitionId = WorkflowDefinitionId.of(entity.getDefinitionId());
                return definitionRepository.findByIdIncludingInactive(definitionId, tenantId)
                                .flatMap(definition -> {
                                        if (definition == null) {
                                                return Uni.createFrom().failure(new NoSuchElementException(
                                                                "WorkflowDefinition not found: "
                                                                                + definitionId.value()));
                                        }
                                        return Uni.createFrom().item(toDomain(entity, definition));
                                });
        }

        private WorkflowRun toDomain(WorkflowRunEntity entity, WorkflowDefinition definition) {
                return WorkflowRunEntityMapper.toDomain(entity, definition);
        }

        private Uni<List<WorkflowRun>> toDomains(List<WorkflowRunEntity> entities) {
                if (entities == null || entities.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                }
                return Multi.createFrom().iterable(entities)
                                .onItem().transformToUniAndConcatenate(this::toDomain)
                                .collect().asList();
        }
}
