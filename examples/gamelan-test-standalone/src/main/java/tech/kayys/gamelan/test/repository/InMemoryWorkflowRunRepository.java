package tech.kayys.gamelan.test.repository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import tech.kayys.gamelan.api.repository.WorkflowRunRepository;
import tech.kayys.gamelan.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryWorkflowRunRepository implements WorkflowRunRepository {

    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
    private final Map<String, ExecutionToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, CallbackRegistration> callbacks = new ConcurrentHashMap<>();

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
        tokens.put(token.value(), token);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateToken(ExecutionToken token) {
        return Uni.createFrom().item(tokens.containsKey(token.value()));
    }

    @Override
    public Uni<Void> storeCallback(CallbackRegistration callback) {
        callbacks.put(callback.callbackToken(), callback);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
        return Uni.createFrom().item(callbacks.containsKey(token));
    }
}
