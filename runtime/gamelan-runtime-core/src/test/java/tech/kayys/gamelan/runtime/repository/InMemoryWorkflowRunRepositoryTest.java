package tech.kayys.gamelan.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryCursor;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryPage;
import tech.kayys.gamelan.engine.repository.contract.WorkflowRunRepositoryContract;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class InMemoryWorkflowRunRepositoryTest implements WorkflowRunRepositoryContract {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Override
    public WorkflowRunRepository newWorkflowRunRepository(WorkflowDefinition definition) {
        return new InMemoryWorkflowRunRepository();
    }

    @Test
    void withLock_failsWhenRunIsMissing() {
        InMemoryWorkflowRunRepository repository = new InMemoryWorkflowRunRepository();

        assertThrows(NoSuchElementException.class, () -> repository
                .withLock(WorkflowRunId.of("missing"), run -> Uni.createFrom().item(run))
                .await()
                .indefinitely());
    }

    @Test
    void updateContextVariable_mutatesRunContext() {
        InMemoryWorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        WorkflowRun run = WorkflowRun.create(TENANT, definition(), Map.of("initial", "value"));
        repository.persist(run).await().indefinitely();

        repository.updateContextVariable(run.getId(), "next", 42).await().indefinitely();

        WorkflowRun restored = repository.findById(run.getId(), TENANT).await().indefinitely();
        assertEquals("value", restored.getContext().getVariable("initial"));
        assertEquals(42, restored.getContext().getVariable("next"));
    }

    @Test
    void updateNodeExecution_mutatesExistingNodeExecution() {
        InMemoryWorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        WorkflowRun run = WorkflowRun.create(TENANT, definition(), Map.of());
        run.start();
        repository.persist(run).await().indefinitely();

        Instant startedAt = Instant.now().minusSeconds(5);
        Instant completedAt = Instant.now();
        repository.updateNodeExecution(run.getId(), NODE_ID, new NodeExecutionSnapshot(
                NODE_ID.value(),
                NodeExecutionStatus.COMPLETED.name(),
                1,
                startedAt,
                completedAt,
                null,
                Map.of("result", "ok"),
                null)).await().indefinitely();

        WorkflowRun restored = repository.findById(run.getId(), TENANT).await().indefinitely();
        assertEquals(NodeExecutionStatus.COMPLETED, restored.getNodeExecution(NODE_ID).getStatus());
        assertEquals(startedAt, restored.getNodeExecution(NODE_ID).getStartedAt());
        assertEquals(completedAt, restored.getNodeExecution(NODE_ID).getCompletedAt());
        assertEquals("ok", restored.getNodeExecution(NODE_ID).getOutput().get("result"));
    }

    @Test
    void queryActiveRunsForRecovery_returnsActiveRunsAcrossTenants() {
        InMemoryWorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        WorkflowRun running = WorkflowRun.create(TENANT, definition(), Map.of());
        running.start();
        WorkflowRun suspended = WorkflowRun.create(TENANT, definition(), Map.of());
        suspended.start();
        suspended.suspend("waiting", NODE_ID);
        WorkflowRun completed = WorkflowRun.create(TENANT, definition(), Map.of());
        completed.start();
        completed.completeNode(NODE_ID, 1, Map.of());

        repository.persist(running).await().indefinitely();
        repository.persist(suspended).await().indefinitely();
        repository.persist(completed).await().indefinitely();

        List<WorkflowRun> runs = repository.queryActiveRunsForRecovery(0, 10).await().indefinitely();

        List<String> expectedIds = List.of(running, suspended).stream()
                .map(run -> run.getId().value())
                .sorted()
                .toList();
        assertEquals(expectedIds, runs.stream().map(run -> run.getId().value()).toList());
    }

    @Test
    void scanActiveRunsForRecovery_usesCursorAcrossActiveRuns() {
        InMemoryWorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        WorkflowRun first = WorkflowRun.create(TENANT, definition(), Map.of());
        first.start();
        WorkflowRun second = WorkflowRun.create(TENANT, definition(), Map.of());
        second.start();

        repository.persist(first).await().indefinitely();
        repository.persist(second).await().indefinitely();

        WorkflowRunRecoveryPage firstPage = repository
                .scanActiveRunsForRecovery(WorkflowRunRecoveryCursor.start(), 1)
                .await()
                .indefinitely();
        WorkflowRunRecoveryPage secondPage = repository
                .scanActiveRunsForRecovery(firstPage.nextCursor(), 1)
                .await()
                .indefinitely();
        WorkflowRunRecoveryPage terminalPage = repository
                .scanActiveRunsForRecovery(secondPage.nextCursor(), 1)
                .await()
                .indefinitely();

        List<String> expectedIds = List.of(first, second).stream()
                .map(run -> run.getId().value())
                .sorted()
                .toList();
        assertEquals(List.of(expectedIds.get(0)), firstPage.runs().stream()
                .map(run -> run.getId().value())
                .toList());
        assertTrue(firstPage.hasMore());
        assertEquals(List.of(expectedIds.get(1)), secondPage.runs().stream()
                .map(run -> run.getId().value())
                .toList());
        assertFalse(secondPage.hasMore());
        assertTrue(terminalPage.runs().isEmpty());
        assertFalse(terminalPage.hasMore());
    }

    @Test
    void tokensAndCallbacks_rejectExpiredEntriesAndKeepFreshEntries() {
        InMemoryWorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        ExecutionToken expiredToken = new ExecutionToken("expired-token", runId, NODE_ID, 1,
                Instant.now().minusSeconds(1));
        ExecutionToken staleToken = new ExecutionToken("stale-token", runId, NODE_ID, 1,
                Instant.now().minusSeconds(1));
        ExecutionToken freshToken = new ExecutionToken("fresh-token", runId, NODE_ID, 1,
                Instant.now().plusSeconds(60));

        repository.storeToken(expiredToken).await().indefinitely();
        assertFalse(repository.validateToken(expiredToken).await().indefinitely());
        assertFalse(repository.validateToken(null).await().indefinitely());

        repository.storeToken(staleToken).await().indefinitely();
        repository.storeToken(freshToken).await().indefinitely();
        assertFalse(repository.validateToken(staleToken).await().indefinitely());
        assertTrue(repository.validateToken(freshToken).await().indefinitely());
        assertTrue(repository.validateToken(new ExecutionToken(
                freshToken.value(),
                runId,
                TenantId.of("tenant-2"),
                NODE_ID,
                1,
                freshToken.expiresAt())).await().indefinitely());

        ExecutionToken tenantToken = new ExecutionToken("tenant-token", runId, TENANT, NODE_ID, 1,
                Instant.now().plusSeconds(60));
        repository.storeToken(tenantToken).await().indefinitely();
        assertTrue(repository.validateToken(tenantToken).await().indefinitely());
        assertFalse(repository.validateToken(new ExecutionToken(
                tenantToken.value(),
                runId,
                TenantId.of("tenant-2"),
                NODE_ID,
                1,
                tenantToken.expiresAt())).await().indefinitely());

        CallbackRegistration expiredCallback = callback("expired-callback-token", runId, -1);
        CallbackRegistration staleCallback = callback("stale-callback-token", runId, -1);
        CallbackRegistration freshCallback = callback("fresh-callback-token", runId, 60);

        repository.storeCallback(expiredCallback).await().indefinitely();
        assertFalse(repository.validateCallback(runId, expiredCallback.callbackToken()).await().indefinitely());
        assertFalse(repository.validateCallback(runId, null).await().indefinitely());
        assertFalse(repository.validateCallback(runId, " ").await().indefinitely());

        repository.storeCallback(staleCallback).await().indefinitely();
        repository.storeCallback(freshCallback).await().indefinitely();
        assertFalse(repository.validateCallback(runId, staleCallback.callbackToken()).await().indefinitely());
        assertFalse(repository.validateCallback(WorkflowRunId.of("other-run"), freshCallback.callbackToken())
                .await().indefinitely());
        assertTrue(repository.validateCallback(runId, freshCallback.callbackToken()).await().indefinitely());

        CallbackRegistration tenantCallback = new CallbackRegistration(
                "tenant-callback-token",
                runId,
                TENANT,
                NODE_ID,
                "http://localhost/callback",
                Instant.now().plusSeconds(60));
        repository.storeCallback(tenantCallback).await().indefinitely();
        assertTrue(repository.validateCallback(runId, TENANT, tenantCallback.callbackToken()).await().indefinitely());
        assertFalse(repository.validateCallback(runId, TenantId.of("tenant-2"), tenantCallback.callbackToken())
                .await().indefinitely());
        assertTrue(repository.validateCallback(runId, tenantCallback.callbackToken()).await().indefinitely());
    }

    private static WorkflowDefinition definition() {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-1"),
                TENANT,
                "test-workflow",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(node()),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node() {
        return new NodeDefinition(
                NODE_ID,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static CallbackRegistration callback(String token, WorkflowRunId runId, long expiresInSeconds) {
        return new CallbackRegistration(token, runId, NODE_ID, "http://localhost/callback",
                Instant.now().plusSeconds(expiresInSeconds));
    }
}
