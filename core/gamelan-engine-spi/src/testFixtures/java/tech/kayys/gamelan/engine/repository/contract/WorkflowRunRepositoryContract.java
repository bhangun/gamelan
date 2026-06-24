package tech.kayys.gamelan.engine.repository.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/**
 * Shared conformance tests for workflow-run persistence, tokens, callbacks, and surgical updates.
 */
public interface WorkflowRunRepositoryContract {

    TenantId CONTRACT_TENANT = TenantId.of("contract-tenant");
    TenantId OTHER_TENANT = TenantId.of("contract-other-tenant");
    NodeId CONTRACT_NODE = NodeId.of("contract-node");

    WorkflowRunRepository newWorkflowRunRepository(WorkflowDefinition definition);

    @Test
    default void workflowRunRepositoryContract_persistsFindsSnapshotsQueriesAndCountsRuns() {
        WorkflowDefinition definition = workflowDefinition("contract-run-repository-main", CONTRACT_TENANT);
        WorkflowRunRepository repository = newWorkflowRunRepository(definition);

        WorkflowRun running = WorkflowRun.create(CONTRACT_TENANT, definition, Map.of("input", "value"));
        running.start();
        WorkflowRun completed = WorkflowRun.create(CONTRACT_TENANT, definition, Map.of("input", "done"));
        completed.start();
        completed.startNode(CONTRACT_NODE, 1);
        completed.completeNode(CONTRACT_NODE, 1, Map.of("result", "ok"));

        repository.persist(running).await().indefinitely();
        repository.persist(completed).await().indefinitely();

        WorkflowRun restored = repository.findById(running.getId(), CONTRACT_TENANT).await().indefinitely();
        WorkflowRunSnapshot snapshot = repository.snapshot(running.getId(), CONTRACT_TENANT).await().indefinitely();
        List<WorkflowRun> runningRuns = repository.query(CONTRACT_TENANT, definition.id(), RunStatus.RUNNING, 0, 10)
                .await()
                .indefinitely();
        List<WorkflowRun> recoverableRuns = repository.queryActiveRunsForRecovery(0, 10).await().indefinitely();

        assertNotNull(restored);
        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals("value", restored.getContext().getVariable("input"));
        assertNotNull(snapshot);
        assertEquals(running.getId(), snapshot.id());
        assertEquals("value", snapshot.variables().get("input"));
        assertEquals(List.of(running.getId()), runningRuns.stream().map(WorkflowRun::getId).toList());
        assertTrue(recoverableRuns.stream().map(WorkflowRun::getId).toList().contains(running.getId()));
        assertFalse(recoverableRuns.stream().map(WorkflowRun::getId).toList().contains(completed.getId()));
        assertEquals(1, repository.countActiveRuns(CONTRACT_TENANT).await().indefinitely());
        assertNull(repository.findById(running.getId(), OTHER_TENANT).await().indefinitely());
    }

    @Test
    default void workflowRunRepositoryContract_appliesSurgicalContextAndNodeUpdates() {
        WorkflowDefinition definition = workflowDefinition("contract-run-repository-surgical", CONTRACT_TENANT);
        WorkflowRunRepository repository = newWorkflowRunRepository(definition);
        WorkflowRun run = WorkflowRun.create(CONTRACT_TENANT, definition, Map.of("initial", "value"));
        run.start();
        repository.persist(run).await().indefinitely();

        Instant startedAt = Instant.now().minusSeconds(5);
        Instant completedAt = Instant.now();
        repository.updateContextVariable(run.getId(), "next", 42).await().indefinitely();
        repository.updateNodeExecution(run.getId(), CONTRACT_NODE, new NodeExecutionSnapshot(
                CONTRACT_NODE.value(),
                NodeExecutionStatus.COMPLETED.name(),
                1,
                startedAt,
                completedAt,
                null,
                Map.of("result", "ok"),
                null)).await().indefinitely();

        WorkflowRun restored = repository.findById(run.getId(), CONTRACT_TENANT).await().indefinitely();

        assertEquals("value", restored.getContext().getVariable("initial"));
        assertEquals(42, restored.getContext().getVariable("next"));
        assertEquals(NodeExecutionStatus.COMPLETED, restored.getNodeExecution(CONTRACT_NODE).getStatus());
        assertEquals(startedAt, restored.getNodeExecution(CONTRACT_NODE).getStartedAt());
        assertEquals(completedAt, restored.getNodeExecution(CONTRACT_NODE).getCompletedAt());
        assertEquals("ok", restored.getNodeExecution(CONTRACT_NODE).getOutput().get("result"));
    }

    @Test
    default void workflowRunRepositoryContract_validatesExecutionTokensWithExpiryAndTenantScope() {
        WorkflowDefinition definition = workflowDefinition("contract-run-repository-tokens", CONTRACT_TENANT);
        WorkflowRunRepository repository = newWorkflowRunRepository(definition);
        WorkflowRunId runId = WorkflowRunId.of("contract-token-run");

        ExecutionToken expiredToken = new ExecutionToken("contract-expired-token", runId, CONTRACT_NODE, 1,
                Instant.now().minusSeconds(1));
        repository.storeToken(expiredToken).await().indefinitely();
        assertFalse(repository.validateToken(expiredToken).await().indefinitely());
        assertFalse(repository.validateToken(null).await().indefinitely());

        ExecutionToken legacyToken = new ExecutionToken("contract-legacy-token", runId, CONTRACT_NODE, 1,
                Instant.now().plusSeconds(60));
        repository.storeToken(legacyToken).await().indefinitely();
        assertTrue(repository.validateToken(new ExecutionToken(
                legacyToken.value(),
                runId,
                OTHER_TENANT,
                CONTRACT_NODE,
                1,
                legacyToken.expiresAt())).await().indefinitely());

        ExecutionToken tenantToken = new ExecutionToken("contract-tenant-token", runId, CONTRACT_TENANT,
                CONTRACT_NODE, 1, Instant.now().plusSeconds(60));
        repository.storeToken(tenantToken).await().indefinitely();

        assertTrue(repository.validateToken(tenantToken).await().indefinitely());
        assertFalse(repository.validateToken(new ExecutionToken(
                tenantToken.value(),
                runId,
                OTHER_TENANT,
                CONTRACT_NODE,
                1,
                tenantToken.expiresAt())).await().indefinitely());
        assertFalse(repository.validateToken(new ExecutionToken(
                tenantToken.value(),
                WorkflowRunId.of("other-run"),
                CONTRACT_TENANT,
                CONTRACT_NODE,
                1,
                tenantToken.expiresAt())).await().indefinitely());
        assertFalse(repository.validateToken(new ExecutionToken(
                tenantToken.value(),
                runId,
                CONTRACT_TENANT,
                NodeId.of("other-node"),
                1,
                tenantToken.expiresAt())).await().indefinitely());
    }

    @Test
    default void workflowRunRepositoryContract_validatesCallbacksWithExpiryRunAndTenantScope() {
        WorkflowDefinition definition = workflowDefinition("contract-run-repository-callbacks", CONTRACT_TENANT);
        WorkflowRunRepository repository = newWorkflowRunRepository(definition);
        WorkflowRunId runId = WorkflowRunId.of("contract-callback-run");

        CallbackRegistration expiredCallback = callback("contract-expired-callback", runId, -1);
        repository.storeCallback(expiredCallback).await().indefinitely();
        assertFalse(repository.validateCallback(runId, expiredCallback.callbackToken()).await().indefinitely());
        assertFalse(repository.validateCallback(runId, null).await().indefinitely());
        assertFalse(repository.validateCallback(runId, " ").await().indefinitely());

        CallbackRegistration legacyCallback = callback("contract-legacy-callback", runId, 60);
        repository.storeCallback(legacyCallback).await().indefinitely();
        assertTrue(repository.validateCallback(runId, legacyCallback.callbackToken()).await().indefinitely());
        assertTrue(repository.validateCallback(runId, OTHER_TENANT, legacyCallback.callbackToken())
                .await()
                .indefinitely());
        assertFalse(repository.validateCallback(WorkflowRunId.of("other-run"), legacyCallback.callbackToken())
                .await()
                .indefinitely());

        CallbackRegistration tenantCallback = new CallbackRegistration(
                "contract-tenant-callback",
                runId,
                CONTRACT_TENANT,
                CONTRACT_NODE,
                "http://localhost/callback",
                Instant.now().plusSeconds(60));
        repository.storeCallback(tenantCallback).await().indefinitely();

        assertTrue(repository.validateCallback(runId, CONTRACT_TENANT, tenantCallback.callbackToken())
                .await()
                .indefinitely());
        assertFalse(repository.validateCallback(runId, OTHER_TENANT, tenantCallback.callbackToken())
                .await()
                .indefinitely());
        assertTrue(repository.validateCallback(runId, tenantCallback.callbackToken()).await().indefinitely());
    }

    @Test
    default void workflowRunRepositoryContract_withLockRejectsMissingRuns() {
        WorkflowDefinition definition = workflowDefinition("contract-run-repository-lock", CONTRACT_TENANT);
        WorkflowRunRepository repository = newWorkflowRunRepository(definition);

        assertThrows(NoSuchElementException.class, () -> repository
                .withLock(WorkflowRunId.of("contract-missing-run"), run -> Uni.createFrom().item(run))
                .await()
                .indefinitely());
    }

    private static WorkflowDefinition workflowDefinition(String id, TenantId tenantId) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(id),
                tenantId,
                id,
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
                CONTRACT_NODE,
                "contract node",
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
        return new CallbackRegistration(
                token,
                runId,
                CONTRACT_NODE,
                "http://localhost/callback",
                Instant.now().plusSeconds(expiresInSeconds));
    }
}
