package tech.kayys.gamelan.core.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

class WorkflowOrchestratorTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    @Test
    void onWorkflowRunUpdated_usesTenantScopedLookupForStructuredPayload() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator();
        orchestrator.runRepository = repository;
        JsonObject payload = JsonObject.mapFrom(WorkflowRunUpdateEvent.of(run.getId(), TENANT, "test"));

        orchestrator.onWorkflowRunUpdated(payload).await().indefinitely();

        assertEquals(0, repository.findCount.get());
        assertEquals(1, repository.tenantFindCount.get());
        assertEquals(TENANT, repository.lastTenant);
    }

    @Test
    void onWorkflowRunUpdated_supportsLegacyStringPayload() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator();
        orchestrator.runRepository = repository;

        orchestrator.onWorkflowRunUpdated("  " + run.getId().value() + "  ").await().indefinitely();

        assertEquals(1, repository.findCount.get());
        assertEquals(0, repository.tenantFindCount.get());
    }

    @Test
    void onWorkflowRunUpdated_ignoresBlankPayload() {
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(null);
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator();
        orchestrator.runRepository = repository;

        orchestrator.onWorkflowRunUpdated(" ").await().indefinitely();

        assertEquals(0, repository.findCount.get());
        assertEquals(0, repository.tenantFindCount.get());
    }

    private static WorkflowDefinition workflow() {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-test"),
                TENANT,
                "test",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(new NodeDefinition(
                        NodeId.of("node-1"),
                        "node-1",
                        NodeType.TASK,
                        "test",
                        Map.of(),
                        List.of(),
                        List.of(),
                        RetryPolicy.none(),
                        null,
                        true)),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static final class RecordingWorkflowRunRepository implements WorkflowRunRepository {
        private final WorkflowRun run;
        private final AtomicInteger findCount = new AtomicInteger();
        private final AtomicInteger tenantFindCount = new AtomicInteger();
        private TenantId lastTenant;

        private RecordingWorkflowRunRepository(WorkflowRun run) {
            this.run = run;
        }

        @Override
        public Uni<WorkflowRun> persist(WorkflowRun run) {
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> update(WorkflowRun run) {
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id) {
            findCount.incrementAndGet();
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
            tenantFindCount.incrementAndGet();
            lastTenant = tenantId;
            return Uni.createFrom().item(run);
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
            return action.apply(run);
        }

        @Override
        public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().item(run.createSnapshot());
        }

        @Override
        public Uni<List<WorkflowRun>> query(
                TenantId tenantId,
                WorkflowDefinitionId definitionId,
                RunStatus status,
                int page,
                int size) {
            return Uni.createFrom().item(run == null ? List.of() : List.of(run));
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
            return Uni.createFrom().item(false);
        }

        @Override
        public Uni<Void> storeCallback(CallbackRegistration callback) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
            return Uni.createFrom().item(false);
        }

        @Override
        public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
            return Uni.createFrom().voidItem();
        }
    }
}
