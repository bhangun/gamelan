package tech.kayys.gamelan.test.client.mock;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryWorkflowRunRepository implements WorkflowRunRepository {

    private final Map<WorkflowRunId, WorkflowRun> runs = new ConcurrentHashMap<>();

    @Override
    public Uni<WorkflowRun> persist(WorkflowRun run) {
        runs.put(run.getId(), run);
        return Uni.createFrom().item(run);
    }

    @Override
    public Uni<WorkflowRun> update(WorkflowRun run) {
        runs.put(run.getId(), run);
        return Uni.createFrom().item(run);
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id) {
        return Uni.createFrom().item(runs.get(id));
    }

    @Override
    public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
        return findById(id);
    }

    @Override
    public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
        WorkflowRun run = runs.get(runId);
        if (run == null) {
            return Uni.createFrom().failure(new RuntimeException("Run not found: " + runId));
        }
        return action.apply(run);
    }

    @Override
    public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<List<WorkflowRun>> query(
            TenantId tenantId,
            WorkflowDefinitionId definitionId,
            RunStatus status,
            int page,
            int size) {
        return Uni.createFrom().item(Collections.emptyList());
    }

    @Override
    public Uni<Long> countActiveRuns(TenantId tenantId) {
        return Uni.createFrom().item(0L);
    }

    @Override
    public Uni<Void> storeToken(ExecutionToken token) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateToken(ExecutionToken token) {
        return Uni.createFrom().item(true);
    }

    @Override
    public Uni<Void> storeCallback(CallbackRegistration callback) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
        return Uni.createFrom().item(true);
    }
}
