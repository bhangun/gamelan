package tech.kayys.gamelan.core.integration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.quarkus.arc.DefaultBean;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeDispatchReservation;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.signal.ExternalSignal;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest;

/**
 * Minimal CDI mock beans required for WorkflowExecutionIntegrationTest.
 * These satisfy unsatisfied dependencies in gamelan-engine-core's test scope
 * (implementations live in gamelan-engine which would create a cyclic dep).
 */
public class TestBeans {

    @Mock
    @ApplicationScoped
    @DefaultBean
    public static class MockWorkflowDefinitionRepository implements WorkflowDefinitionRepository {
        private record Key(String t, String d) {}
        private final Map<Key, WorkflowDefinition> store = new ConcurrentHashMap<>();

        @Override public Uni<WorkflowDefinition> findById(WorkflowDefinitionId id, TenantId t) {
            return Uni.createFrom().item(store.get(new Key(t.value(), id.value())));
        }
        @Override public Uni<WorkflowDefinition> save(WorkflowDefinition d, TenantId t) {
            store.put(new Key(t.value(), d.id().value()), d); return Uni.createFrom().item(d);
        }
        @Override public Uni<List<WorkflowDefinition>> findByTenant(TenantId t, boolean activeOnly) {
            return Uni.createFrom().item(store.values().stream().filter(d -> d.tenantId().equals(t)).toList());
        }
        @Override public Uni<WorkflowDefinition> findByName(String name, TenantId t) {
            return Uni.createFrom().item(store.values().stream()
                    .filter(d -> d.tenantId().equals(t) && d.name().equals(name)).findFirst().orElse(null));
        }
        @Override public Uni<Void> delete(WorkflowDefinitionId id, TenantId t) {
            store.remove(new Key(t.value(), id.value())); return Uni.createFrom().voidItem();
        }
    }

    @Mock
    @ApplicationScoped
    @DefaultBean
    public static class MockWorkflowRunRepository implements WorkflowRunRepository {
        private final Map<String, WorkflowRun> store = new ConcurrentHashMap<>();

        @Override public Uni<WorkflowRun> persist(WorkflowRun run) { store.put(run.getId().value(), run); return Uni.createFrom().item(run); }
        @Override public Uni<WorkflowRun> update(WorkflowRun run) { store.put(run.getId().value(), run); return Uni.createFrom().item(run); }
        @Override public Uni<WorkflowRun> findById(WorkflowRunId id) { return Uni.createFrom().item(store.get(id.value())); }
        @Override public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId t) { return findById(id); }
        @Override public <T> Uni<T> withLock(WorkflowRunId id, java.util.function.Function<WorkflowRun, Uni<T>> action) {
            WorkflowRun run = store.get(id.value());
            return run != null ? action.apply(run) : Uni.createFrom().failure(new java.util.NoSuchElementException(id.value()));
        }
        @Override public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId id, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<List<WorkflowRun>> query(TenantId t, WorkflowDefinitionId defId, RunStatus s, int page, int size) {
            return Uni.createFrom().item(store.values().stream().toList());
        }
        @Override public Uni<Long> countActiveRuns(TenantId t) { return Uni.createFrom().item(0L); }
        @Override public Uni<Void> storeToken(ExecutionToken token) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Boolean> validateToken(ExecutionToken token) { return Uni.createFrom().item(true); }
        @Override public Uni<Void> storeCallback(CallbackRegistration cb) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Boolean> validateCallback(WorkflowRunId id, String token) { return Uni.createFrom().item(true); }
        @Override public Uni<Void> updateContextVariable(WorkflowRunId id, String key, Object value) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> updateNodeExecution(WorkflowRunId id, tech.kayys.gamelan.engine.node.NodeId nodeId, tech.kayys.gamelan.engine.node.NodeExecutionSnapshot snap) { return Uni.createFrom().voidItem(); }
    }

    @Mock
    @ApplicationScoped
    @DefaultBean
    public static class MockWorkflowDefinitionService implements WorkflowDefinitionService {
        @Override public Uni<WorkflowDefinition> create(CreateWorkflowDefinitionRequest r, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<WorkflowDefinition> get(WorkflowDefinitionId id, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<List<WorkflowDefinition>> list(TenantId t, boolean activeOnly) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<WorkflowDefinition> getByName(String name, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<WorkflowDefinition> update(WorkflowDefinitionId id, UpdateWorkflowDefinitionRequest r, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<Void> delete(WorkflowDefinitionId id, TenantId t) { return Uni.createFrom().voidItem(); }
    }

    @Mock
    @ApplicationScoped
    @DefaultBean
    public static class MockWorkflowRunManager implements WorkflowRunManager {

        @Override public Uni<WorkflowRun> createRun(CreateRunRequest r) {
            // Return a stub — integration test only verifies non-null and status
            return Uni.createFrom().failure(new UnsupportedOperationException(
                    "MockWorkflowRunManager: use a real engine for full integration tests"));
        }
        @Override public Uni<WorkflowRun> startRun(WorkflowRunId id, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<WorkflowRun> suspendRun(WorkflowRunId id, TenantId t, String reason, NodeId node) { return Uni.createFrom().nullItem(); }
        @Override public Uni<WorkflowRun> resumeRun(WorkflowRunId id, TenantId t, Map<String, Object> data, String taskId) { return Uni.createFrom().nullItem(); }
        @Override public Uni<Void> cancelRun(WorkflowRunId id, TenantId t, String reason) { return Uni.createFrom().voidItem(); }
        @Override public Uni<WorkflowRun> completeRun(WorkflowRunId id, TenantId t, Map<String, Object> outputs) { return Uni.createFrom().nullItem(); }
        @Override public Uni<WorkflowRun> failRun(WorkflowRunId id, TenantId t, ErrorInfo e) { return Uni.createFrom().nullItem(); }
        @Override public Uni<Void> completeCompensation(WorkflowRunId id, TenantId t) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> failCompensation(WorkflowRunId id, TenantId t, ErrorInfo e) { return Uni.createFrom().voidItem(); }
        @Override public Uni<NodeDispatchReservation> reserveNodeForDispatch(WorkflowRunId id, TenantId t, NodeId node) {
            return Uni.createFrom().item(NodeDispatchReservation.skipped(id, t, node, "mock"));
        }
        @Override public Uni<Void> failNodeExecution(WorkflowRunId id, TenantId t, NodeId node, int attempt, ErrorInfo e, String reason) {
            return Uni.createFrom().voidItem();
        }
        @Override public Uni<Void> handleNodeResult(WorkflowRunId id, NodeExecutionResult r) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> signal(WorkflowRunId id, Signal s) { return Uni.createFrom().voidItem(); }
        @Override public Uni<WorkflowRun> getRun(WorkflowRunId id, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<WorkflowRunSnapshot> getSnapshot(WorkflowRunId id, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<ExecutionHistory> getExecutionHistory(WorkflowRunId id, TenantId t) { return Uni.createFrom().nullItem(); }
        @Override public Uni<List<WorkflowRun>> queryRuns(TenantId t, WorkflowDefinitionId defId, RunStatus s, int page, int size) { return Uni.createFrom().item(List.of()); }
        @Override public Uni<Long> getActiveRunsCount(TenantId t) { return Uni.createFrom().item(0L); }
        @Override public Uni<ValidationResult> validateTransition(WorkflowRunId id, RunStatus s) { return Uni.createFrom().item(ValidationResult.success()); }
        @Override public Uni<ExecutionToken> createExecutionToken(WorkflowRunId id, NodeId node, int attempt) { return Uni.createFrom().nullItem(); }
        @Override public Uni<Void> onNodeExecutionCompleted(NodeExecutionResult r, String sig) { return Uni.createFrom().voidItem(); }
        @Override public Uni<Void> onExternalSignal(WorkflowRunId id, ExternalSignal s, String token) { return Uni.createFrom().voidItem(); }
        @Override public Uni<CallbackRegistration> registerCallback(WorkflowRunId id, NodeId node, CallbackConfig cfg) { return Uni.createFrom().nullItem(); }
    }
}
