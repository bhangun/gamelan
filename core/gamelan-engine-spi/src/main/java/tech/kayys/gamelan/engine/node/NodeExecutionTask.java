package tech.kayys.gamelan.engine.node;

import java.util.Map;

import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Node Execution Task - Task scheduled for execution
 */
public record NodeExecutionTask(
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        ExecutionToken token,
        Map<String, Object> context,
        RetryPolicy retryPolicy) {
}
