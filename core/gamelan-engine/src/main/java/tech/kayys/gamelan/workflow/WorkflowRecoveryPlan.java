package tech.kayys.gamelan.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowReplayConsistencyChecker;

public record WorkflowRecoveryPlan(
        List<NodeId> dueRetryNodes,
        List<RetryWakeup> retryWakeups,
        List<StaleNodeExecution> staleExecutions,
        WorkflowReplayConsistencyChecker.Report replayConsistency) {

    public WorkflowRecoveryPlan(
            List<NodeId> dueRetryNodes,
            List<RetryWakeup> retryWakeups,
            List<StaleNodeExecution> staleExecutions) {
        this(dueRetryNodes, retryWakeups, staleExecutions, null);
    }

    public WorkflowRecoveryPlan {
        dueRetryNodes = dueRetryNodes != null ? List.copyOf(dueRetryNodes) : List.of();
        retryWakeups = retryWakeups != null ? List.copyOf(retryWakeups) : List.of();
        staleExecutions = staleExecutions != null ? List.copyOf(staleExecutions) : List.of();
    }

    public static WorkflowRecoveryPlan empty() {
        return new WorkflowRecoveryPlan(List.of(), List.of(), List.of(), null);
    }

    public boolean hasWork() {
        return hasReplayConsistencyBlock() || !dueRetryNodes.isEmpty() || !retryWakeups.isEmpty()
                || !staleExecutions.isEmpty();
    }

    public boolean hasReplayConsistencyBlock() {
        return replayConsistency != null && !replayConsistency.consistent();
    }

    public boolean hasReplayDrift() {
        return replayConsistency != null && replayConsistency.drift();
    }

    public boolean hasReplayUnavailable() {
        return replayConsistency != null && replayConsistency.unavailable();
    }

    public record RetryWakeup(
            NodeId nodeId,
            int attempt,
            Instant retryAt) {
    }

    public record StaleNodeExecution(
            NodeId nodeId,
            int attempt,
            Instant startedAt,
            Instant deadline,
            Duration timeout,
            Duration grace) {
    }
}
