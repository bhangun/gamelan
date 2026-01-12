package tech.kayys.silat.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

interface ExecutionTokenService {

    Uni<ExecutionToken> issue(WorkflowRunId runId, NodeId nodeId, int attempt);

    Uni<Boolean> verifySignature(NodeExecutionResult result, String signature);
}
