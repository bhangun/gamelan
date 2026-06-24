package tech.kayys.gamelan.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowReplayConsistencyChecker;

@ApplicationScoped
public class WorkflowRecoveryAnalyzer {

    public WorkflowRecoveryPlan analyze(
            WorkflowRun run,
            Instant now,
            Duration timeoutGrace) {
        return analyze(run, now, timeoutGrace, null);
    }

    public WorkflowRecoveryPlan analyze(
            WorkflowRun run,
            Instant now,
            Duration timeoutGrace,
            WorkflowReplayConsistencyChecker.Report replayConsistency) {
        if (run == null || run.getStatus() != RunStatus.RUNNING) {
            return WorkflowRecoveryPlan.empty();
        }

        Instant safeNow = now != null ? now : Instant.now();
        Duration safeGrace = nonNegative(timeoutGrace);
        List<NodeId> dueRetryNodes = new ArrayList<>();
        List<WorkflowRecoveryPlan.RetryWakeup> retryWakeups = new ArrayList<>();
        List<WorkflowRecoveryPlan.StaleNodeExecution> staleExecutions = new ArrayList<>();

        for (Map.Entry<NodeId, NodeExecution> entry : run.getAllNodeExecutions().entrySet()) {
            NodeId nodeId = entry.getKey();
            NodeExecution execution = entry.getValue();
            if (execution == null) {
                continue;
            }
            if (execution.canRetry() && execution.getRetryAt() != null) {
                if (execution.isRetryDue(safeNow)) {
                    dueRetryNodes.add(nodeId);
                } else {
                    retryWakeups.add(new WorkflowRecoveryPlan.RetryWakeup(
                            nodeId,
                            execution.getAttempt(),
                            execution.getRetryAt()));
                }
            }
            staleExecution(nodeId, execution, safeNow, safeGrace).ifPresent(staleExecutions::add);
        }

        return new WorkflowRecoveryPlan(dueRetryNodes, retryWakeups, staleExecutions, replayConsistency);
    }

    private Optional<WorkflowRecoveryPlan.StaleNodeExecution> staleExecution(
            NodeId nodeId,
            NodeExecution execution,
            Instant now,
            Duration grace) {
        if (!isInFlight(execution.getStatus()) || execution.getStartedAt() == null) {
            return Optional.empty();
        }

        NodeDefinition definition = execution.getDefinition();
        Duration timeout = definition != null ? definition.timeout() : Duration.ZERO;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Optional.empty();
        }

        Instant deadline = execution.getStartedAt().plus(timeout).plus(grace);
        if (deadline.isAfter(now)) {
            return Optional.empty();
        }

        return Optional.of(new WorkflowRecoveryPlan.StaleNodeExecution(
                nodeId,
                execution.getAttempt(),
                execution.getStartedAt(),
                deadline,
                timeout,
                grace));
    }

    private boolean isInFlight(NodeExecutionStatus status) {
        return status == NodeExecutionStatus.RUNNING || status == NodeExecutionStatus.EXECUTING;
    }

    private Duration nonNegative(Duration duration) {
        return duration != null && !duration.isNegative() ? duration : Duration.ZERO;
    }
}
