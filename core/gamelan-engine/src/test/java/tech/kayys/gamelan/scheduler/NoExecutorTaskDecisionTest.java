package tech.kayys.gamelan.scheduler;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.registry.ExecutorSelectionRequest;
import tech.kayys.gamelan.registry.ExecutorSelectionReport;

class NoExecutorTaskDecisionTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("decision-run");
    private static final NodeId NODE_ID = NodeId.of("decision-node");
    private static final Instant FIRST_SEEN = Instant.parse("2026-05-27T00:00:00Z");

    @Test
    void defersTransientReasonBelowBudget() {
        NoExecutorTaskDecision decision = NoExecutorTaskDecision.evaluate(
                queuedTask(2, 1),
                report(Map.of(CAPACITY_SATURATED, 1)),
                2);

        assertFalse(decision.shouldDeadLetter());
        assertEquals(NoExecutorTaskDecision.ACTION_DEFER, decision.action());
        assertEquals(CAPACITY_SATURATED, decision.reason());
        assertFalse(decision.permanentSelectionFailure());
        assertFalse(decision.deferBudgetExhausted());
    }

    @Test
    void deadLettersTransientReasonWhenBudgetIsExhausted() {
        NoExecutorTaskDecision decision = NoExecutorTaskDecision.evaluate(
                queuedTask(3, 2),
                report(Map.of(CAPACITY_SATURATED, 1)),
                2);

        assertTrue(decision.shouldDeadLetter());
        assertEquals(NoExecutorTaskDecision.ACTION_DEAD_LETTER, decision.action());
        assertEquals(CAPACITY_SATURATED, decision.reason());
        assertFalse(decision.permanentSelectionFailure());
        assertTrue(decision.deferBudgetExhausted());
    }

    @Test
    void deadLettersPermanentReasonImmediately() {
        NoExecutorTaskDecision decision = NoExecutorTaskDecision.evaluate(
                queuedTask(1, 0),
                report(Map.of(INVALID_CAPACITY_METADATA, 1)),
                30);

        assertTrue(decision.shouldDeadLetter());
        assertEquals(INVALID_CAPACITY_METADATA, decision.reason());
        assertTrue(decision.permanentSelectionFailure());
        assertFalse(decision.deferBudgetExhausted());
    }

    @Test
    void diagnosticsMergeSelectionContextAndWorkerDecision() {
        NoExecutorTaskDecision decision = NoExecutorTaskDecision.evaluate(
                queuedTask(3, 2),
                report(Map.of(CAPACITY_SATURATED, 1)),
                2);

        Map<String, Object> diagnostics = decision.diagnostics(report(Map.of(CAPACITY_SATURATED, 1)));

        assertEquals("decision-node", diagnostics.get("nodeId"));
        assertEquals(CAPACITY_SATURATED, diagnostics.get("primaryRejectionReason"));
        assertEquals(NoExecutorTaskDecision.ACTION_DEAD_LETTER,
                diagnostics.get(NoExecutorTaskDecision.DIAGNOSTIC_WORKER_DECISION));
        assertEquals(CAPACITY_SATURATED,
                diagnostics.get(NoExecutorTaskDecision.DIAGNOSTIC_SELECTION_REASON));
        assertEquals(false,
                diagnostics.get(NoExecutorTaskDecision.DIAGNOSTIC_PERMANENT_SELECTION_FAILURE));
        assertEquals(true,
                diagnostics.get(NoExecutorTaskDecision.DIAGNOSTIC_DEFER_BUDGET_EXHAUSTED));
        assertEquals(2, diagnostics.get(NoExecutorTaskDecision.DIAGNOSTIC_DEFER_COUNT));
        assertEquals(2, diagnostics.get(NoExecutorTaskDecision.DIAGNOSTIC_MAX_DEFERS));
        assertEquals(3, diagnostics.get(NoExecutorTaskDecision.DIAGNOSTIC_DELIVERY_ATTEMPT));
    }

    private static TaskQueue.QueuedTask queuedTask(int deliveryAttempt, int deferCount) {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY, deliveryAttempt);
        context.put(TaskQueueMetadata.DEFER_COUNT_KEY, deferCount);
        context.put(TaskQueueMetadata.FIRST_SEEN_AT_KEY, FIRST_SEEN.toString());
        return new TaskQueue.QueuedTask("message-1", new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                null,
                context,
                RetryPolicy.none()));
    }

    private static ExecutorSelectionReport report(Map<String, Integer> rejectionCounts) {
        return new ExecutorSelectionReport(
                ExecutorSelectionRequest.forNodeType(NODE_ID, "agent", ExecutorPlacementRequirements.none()),
                Optional.empty(),
                1,
                0,
                0,
                1,
                1,
                1,
                0,
                rejectionCounts,
                Map.of("registry", "decision-test"));
    }
}
