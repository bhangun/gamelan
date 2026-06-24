package tech.kayys.gamelan.test.repository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenHash;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryWorkflowRunRepository implements WorkflowRunRepository {

    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
    private final Map<String, StoredExecutionToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, StoredCallbackRegistration> callbacks = new ConcurrentHashMap<>();

    @Override
    public Uni<WorkflowRun> persist(WorkflowRun run) {
        runs.put(run.getId().value(), run);
        return Uni.createFrom().item(run);
    }

    @Override
    public Uni<WorkflowRun> update(WorkflowRun run) {
        runs.put(run.getId().value(), run);
        return Uni.createFrom().item(run);
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id) {
        return Uni.createFrom().item(runs.get(id.value()));
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
        WorkflowRun run = runs.get(id.value());
        if (run != null && run.getTenantId().equals(tenantId)) {
            return Uni.createFrom().item(run);
        }
        return Uni.createFrom().nullItem();
    }

    @Override
    public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
        WorkflowRun run = runs.get(runId.value());
        return action.apply(run);
    }

    @Override
    public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
        return findById(runId, tenantId).map(run -> run != null ? run.createSnapshot() : null);
    }

    @Override
    public Uni<List<WorkflowRun>> query(TenantId tenantId, WorkflowDefinitionId definitionId, RunStatus status, int page, int size) {
        return Uni.createFrom().item(runs.values().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .filter(r -> definitionId == null || r.getDefinitionId().equals(definitionId))
                .filter(r -> status == null || r.getStatus() == status)
                .toList());
    }

    @Override
    public Uni<Long> countActiveRuns(TenantId tenantId) {
        return Uni.createFrom().item(runs.values().stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .filter(r -> r.getStatus() == RunStatus.RUNNING || r.getStatus() == RunStatus.PENDING)
                .count());
    }

    @Override
    public Uni<Void> storeToken(ExecutionToken token) {
        tokens.put(ExecutionTokenHash.sha256(token.value()), StoredExecutionToken.from(token));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateToken(ExecutionToken token) {
        if (token == null) {
            return Uni.createFrom().item(false);
        }
        StoredExecutionToken stored = tokens.get(ExecutionTokenHash.sha256(token.value()));
        return Uni.createFrom().item(stored != null
                && !stored.isExpired()
                && stored.matches(token));
    }

    @Override
    public Uni<Void> storeCallback(CallbackRegistration callback) {
        callbacks.put(BearerTokenHash.sha256(callback.callbackToken()), StoredCallbackRegistration.from(callback));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
        if (runId == null || token == null || token.isBlank()) {
            return Uni.createFrom().item(false);
        }

        StoredCallbackRegistration stored = callbacks.get(BearerTokenHash.sha256(token));
        return Uni.createFrom().item(stored != null
                && !stored.isExpired()
                && stored.runId().equals(runId));
    }

    @Override
    public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
        return Uni.createFrom().voidItem();
    }

    private record StoredExecutionToken(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Instant expiresAt) {

        static StoredExecutionToken from(ExecutionToken token) {
            return new StoredExecutionToken(
                    token.runId(),
                    token.tenantId(),
                    token.nodeId(),
                    token.attempt(),
                    token.expiresAt());
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        boolean matches(ExecutionToken token) {
            return runId.equals(token.runId())
                    && tenantMatches(token)
                    && nodeId.equals(token.nodeId())
                    && attempt == token.attempt();
        }

        private boolean tenantMatches(ExecutionToken token) {
            return tenantId == null || tenantId.equals(token.tenantId());
        }
    }

    private record StoredCallbackRegistration(
            WorkflowRunId runId,
            Instant expiresAt) {

        static StoredCallbackRegistration from(CallbackRegistration callback) {
            return new StoredCallbackRegistration(callback.runId(), callback.expiresAt());
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
