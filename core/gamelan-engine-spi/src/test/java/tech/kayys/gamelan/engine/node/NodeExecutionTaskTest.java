package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.collaboration.CollaborationContext;
import tech.kayys.gamelan.engine.collaboration.ParticipantIsolation;
import tech.kayys.gamelan.engine.collaboration.ParticipantKind;
import tech.kayys.gamelan.engine.collaboration.ParticipantRuntime;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class NodeExecutionTaskTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void workflowVariablesAndNodeConfigurationExposeStringKeyedContextMaps() {
        Map<Object, Object> workflowVariables = new HashMap<>();
        workflowVariables.put("topic", "orders");
        workflowVariables.put(7, "ignored");

        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.WORKFLOW_VARIABLES_KEY, workflowVariables);
        context.put(NodeExecutionTask.NODE_CONFIGURATION_KEY, Map.of("language", "javascript"));

        NodeExecutionTask task = task(context);

        assertEquals("orders", task.workflowVariables().get("topic"));
        assertFalse(task.workflowVariables().containsKey("7"));
        assertEquals("javascript", task.nodeConfiguration().get("language"));
    }

    @Test
    void workflowVariablesAndNodeConfigurationReturnEmptyMapsWhenContextIsMissing() {
        NodeExecutionTask task = task(null);

        assertTrue(task.context().isEmpty());
        assertTrue(task.workflowVariables().isEmpty());
        assertTrue(task.nodeConfiguration().isEmpty());
    }

    @Test
    void constructorDefaultsMissingRetryPolicyToNoRetry() {
        NodeExecutionTask task = new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                new ExecutionToken("token-1", RUN_ID, NODE_ID, 1, Instant.now().plusSeconds(60)),
                null,
                null);

        assertEquals(RetryPolicy.none(), task.retryPolicy());
    }

    @Test
    void constructorAllowsTokenlessInternalTasks() {
        NodeExecutionTask task = new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                null,
                Map.of(),
                RetryPolicy.none());

        assertNull(task.token());
    }

    @Test
    void taskIdAndIdempotencyKeyUseStableRunNodeAttemptIdentity() {
        NodeExecutionTask task = task(Map.of());

        assertEquals("run-1:node-1:1", task.taskId());
        assertEquals(task.taskId(), task.idempotencyKey());
        assertEquals("run-1:node-1:3", NodeExecutionTask.taskId(RUN_ID, NODE_ID, 3));
    }

    @Test
    void constructorRejectsNonPositiveAttempt() {
        GamelanException error = assertThrows(GamelanException.class, () -> new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                0,
                token(1),
                Map.of(),
                RetryPolicy.none()));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionTask attempt must be positive", error.getSafeMessage());
    }

    @Test
    void taskIdRejectsNonPositiveAttempt() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> NodeExecutionTask.taskId(RUN_ID, NODE_ID, 0));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionTask attempt must be positive", error.getSafeMessage());
    }

    @Test
    void constructorRejectsTokenThatDoesNotMatchTaskIdentity() {
        GamelanException error = assertThrows(GamelanException.class, () -> new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                2,
                token(1),
                Map.of(),
                RetryPolicy.none()));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("ExecutionToken must match task identity", error.getSafeMessage());
    }

    @Test
    void constructorRejectsTenantBoundTokenThatDoesNotMatchTaskContextTenant() {
        ExecutionToken token = new ExecutionToken(
                "token-1",
                RUN_ID,
                TenantId.of("tenant-a"),
                NODE_ID,
                1,
                Instant.now().plusSeconds(60));

        GamelanException error = assertThrows(GamelanException.class, () -> new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                token,
                Map.of(NodeExecutionTask.TENANT_ID_KEY, "tenant-b"),
                RetryPolicy.none()));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("ExecutionToken tenant must match task context tenant", error.getSafeMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesContext() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("topic", "orders");
        List<String> tags = new ArrayList<>(List.of("agent"));

        Map<String, Object> context = new HashMap<>();
        context.put("missing", null);
        context.put("nested", nested);
        context.put("tags", tags);

        NodeExecutionTask task = task(context);

        context.put("late", "ignored");
        nested.put("topic", "mutated");
        tags.add("mutated");

        assertFalse(task.context().containsKey("late"));
        assertNull(task.context().get("missing"));
        assertEquals("orders", ((Map<String, Object>) task.context().get("nested")).get("topic"));
        assertEquals(List.of("agent"), task.context().get("tags"));
        assertThrows(UnsupportedOperationException.class, () -> task.context().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) task.context().get("nested")).put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<String>) task.context().get("tags")).add("x"));
    }

    @Test
    void collaborationContextParsesMultiAgentAndHumanParticipants() {
        NodeExecutionTask task = task(Map.of(
                NodeExecutionTask.COLLABORATION_CONTEXT_KEY,
                Map.of(
                        "id", "collab-1",
                        "participants", List.of(
                                Map.of(
                                        "id", "agent:researcher",
                                        "kind", "agent",
                                        "runtime", "distributed",
                                        "isolation", "sandbox",
                                        "roles", List.of("researcher")),
                                Map.of(
                                        "id", "human:reviewer",
                                        "kind", "human",
                                        "runtime", "external",
                                        "roles", List.of("approver"))))));

        CollaborationContext context = task.collaborationContext().orElseThrow();

        assertEquals("collab-1", context.collaborationId());
        assertEquals(2, context.participants().size());
        assertEquals(ParticipantKind.AGENT, context.participants().getFirst().kind());
        assertEquals(ParticipantRuntime.DISTRIBUTED, context.participants().getFirst().runtime());
        assertEquals(ParticipantIsolation.SANDBOX, context.participants().getFirst().isolation());
        assertEquals(1, context.participantsByKind(ParticipantKind.HUMAN).size());
        assertTrue(context.hasSandboxedParticipant());
    }

    @Test
    void executorSelectionPolicyParsesTaskContext() {
        NodeExecutionTask task = task(Map.of(
                NodeExecutionTask.EXECUTOR_SELECTION_KEY,
                Map.of(
                        ExecutorSelectionPolicy.CONTEXT_STRATEGY_KEY, "weighted",
                        ExecutorSelectionPolicy.CONTEXT_REQUIRED_CAPABILITIES_KEY, List.of("coding", "sandbox"),
                        ExecutorSelectionPolicy.CONTEXT_PREFERRED_CAPABILITIES_KEY, List.of("browser"),
                        ExecutorSelectionPolicy.CONTEXT_EXCLUDED_CAPABILITIES_KEY, List.of("finance"),
                        ExecutorSelectionPolicy.CONTEXT_MIN_MEMORY_MB_KEY, 4096,
                        ExecutorSelectionPolicy.CONTEXT_REGIONS_KEY, List.of("us-east-1"),
                        "pool", "sandboxed-agents")));

        ExecutorSelectionPolicy policy = task.executorSelectionPolicy();

        assertEquals("weighted", policy.strategy());
        assertEquals(java.util.Set.of("coding", "sandbox"), policy.requiredCapabilities());
        assertEquals(java.util.Set.of("browser"), policy.preferredCapabilities());
        assertEquals(java.util.Set.of("finance"), policy.excludedCapabilities());
        assertEquals(4096L, policy.resourceRequirements().minMemoryMb());
        assertEquals(java.util.Set.of("us-east-1"), policy.resourceRequirements().regions());
        assertEquals("sandboxed-agents", policy.context().get("pool"));
        assertEquals("weighted", policy.toSelectionContext().get(
                ExecutorSelectionPolicy.CONTEXT_SELECTION_STRATEGY_KEY));
    }

    private static NodeExecutionTask task(Map<String, Object> context) {
        return new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                token(1),
                context,
                RetryPolicy.none());
    }

    private static ExecutionToken token(int attempt) {
        return new ExecutionToken("token-1", RUN_ID, NODE_ID, attempt, Instant.now().plusSeconds(60));
    }
}
