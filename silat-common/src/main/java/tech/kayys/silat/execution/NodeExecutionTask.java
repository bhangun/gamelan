package tech.kayys.silat.execution;

import java.util.Map;

import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.RetryPolicy;
import tech.kayys.silat.model.WorkflowRunId;

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
