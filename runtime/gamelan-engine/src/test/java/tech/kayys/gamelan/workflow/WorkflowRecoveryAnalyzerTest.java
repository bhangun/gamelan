package tech.kayys.gamelan.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowReplayConsistencyChecker;

class WorkflowRecoveryAnalyzerTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");
    private final WorkflowRecoveryAnalyzer analyzer = new WorkflowRecoveryAnalyzer();

    @Test
    void analyze_detectsDueRetryNodes() {
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        WorkflowRun run = runningRun(node(RetryPolicy.DEFAULT, Duration.ZERO));
        run.reserveNodeForDispatch(NODE_ID);
        run.failNode(NODE_ID, 1, error());
        run.getNodeExecution(NODE_ID).setRetryAt(now.minusSeconds(1));

        WorkflowRecoveryPlan plan = analyzer.analyze(run, now, Duration.ZERO);

        assertEquals(List.of(NODE_ID), plan.dueRetryNodes());
        assertTrue(plan.retryWakeups().isEmpty());
        assertTrue(plan.staleExecutions().isEmpty());
    }

    @Test
    void analyze_backfillsRetryWakeupsThatAreNotDue() {
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        WorkflowRun run = runningRun(node(RetryPolicy.DEFAULT, Duration.ZERO));
        run.reserveNodeForDispatch(NODE_ID);
        run.failNode(NODE_ID, 1, error());
        Instant retryAt = now.plusSeconds(60);
        run.getNodeExecution(NODE_ID).setRetryAt(retryAt);

        WorkflowRecoveryPlan plan = analyzer.analyze(run, now, Duration.ZERO);

        assertTrue(plan.dueRetryNodes().isEmpty());
        assertEquals(1, plan.retryWakeups().size());
        assertEquals(NODE_ID, plan.retryWakeups().getFirst().nodeId());
        assertEquals(2, plan.retryWakeups().getFirst().attempt());
        assertEquals(retryAt, plan.retryWakeups().getFirst().retryAt());
        assertTrue(plan.staleExecutions().isEmpty());
    }

    @Test
    void analyze_detectsStaleInFlightExecutionsAfterTimeoutAndGrace() {
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ofSeconds(10)));
        run.reserveNodeForDispatch(NODE_ID);
        run.getNodeExecution(NODE_ID).setStartedAt(now.minusSeconds(45));

        WorkflowRecoveryPlan plan = analyzer.analyze(run, now, Duration.ofSeconds(30));

        assertTrue(plan.dueRetryNodes().isEmpty());
        assertTrue(plan.retryWakeups().isEmpty());
        assertEquals(1, plan.staleExecutions().size());
        assertEquals(NODE_ID, plan.staleExecutions().getFirst().nodeId());
        assertEquals(1, plan.staleExecutions().getFirst().attempt());
    }

    @Test
    void analyze_doesNotReapNodesWithoutExplicitTimeout() {
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ZERO));
        run.reserveNodeForDispatch(NODE_ID);
        run.getNodeExecution(NODE_ID).setStartedAt(now.minusSeconds(3600));
        run.getNodeExecution(NODE_ID).setStatus(NodeExecutionStatus.RUNNING);

        WorkflowRecoveryPlan plan = analyzer.analyze(run, now, Duration.ZERO);

        assertTrue(plan.dueRetryNodes().isEmpty());
        assertTrue(plan.retryWakeups().isEmpty());
        assertTrue(plan.staleExecutions().isEmpty());
    }

    @Test
    void analyze_marksPlanAsWorkWhenReplayDriftExists() {
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ZERO));
        WorkflowReplayConsistencyChecker.Report drift = new WorkflowReplayConsistencyChecker.Report(
                run.getId(),
                List.of(new WorkflowReplayConsistencyChecker.Mismatch("status", "RUNNING", "COMPLETED")));

        WorkflowRecoveryPlan plan = analyzer.analyze(run, now, Duration.ZERO, drift);

        assertTrue(plan.hasReplayConsistencyBlock());
        assertTrue(plan.hasReplayDrift());
        assertTrue(plan.hasWork());
        assertEquals(drift, plan.replayConsistency());
        assertTrue(plan.dueRetryNodes().isEmpty());
        assertTrue(plan.retryWakeups().isEmpty());
        assertTrue(plan.staleExecutions().isEmpty());
    }

    @Test
    void analyze_marksPlanAsWorkWhenReplayUnavailableExists() {
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ZERO));
        WorkflowReplayConsistencyChecker.Report unavailable = new WorkflowReplayConsistencyChecker.Report(
                run.getId(),
                WorkflowReplayConsistencyChecker.Status.UNAVAILABLE,
                List.of(new WorkflowReplayConsistencyChecker.Mismatch(
                        "replayConsistency",
                        "available",
                        "EventStore unavailable")));

        WorkflowRecoveryPlan plan = analyzer.analyze(run, now, Duration.ZERO, unavailable);

        assertTrue(plan.hasReplayConsistencyBlock());
        assertFalse(plan.hasReplayDrift());
        assertTrue(plan.hasReplayUnavailable());
        assertTrue(plan.hasWork());
        assertEquals(unavailable, plan.replayConsistency());
        assertTrue(plan.dueRetryNodes().isEmpty());
        assertTrue(plan.retryWakeups().isEmpty());
        assertTrue(plan.staleExecutions().isEmpty());
    }

    private static WorkflowRun runningRun(NodeDefinition node) {
        WorkflowDefinition definition = workflow(node);
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        return run;
    }

    private static NodeDefinition node(RetryPolicy retryPolicy, Duration timeout) {
        return new NodeDefinition(
                NODE_ID,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                retryPolicy,
                timeout,
                false);
    }

    private static WorkflowDefinition workflow(NodeDefinition node) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-1"),
                TENANT,
                "test-workflow",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(node),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static ErrorInfo error() {
        return new ErrorInfo("TEST_ERROR", "boom", "", Map.of());
    }
}
