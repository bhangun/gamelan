package tech.kayys.gamelan.engine.execution;

import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record ExecutionResponse(
        WorkflowRunId runId,
        RunStatus status,
        Object output,
        String message) {
}