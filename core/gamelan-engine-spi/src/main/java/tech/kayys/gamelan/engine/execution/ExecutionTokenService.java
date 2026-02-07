package tech.kayys.gamelan.engine.execution;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface ExecutionTokenService {

    Uni<ExecutionToken> issue(WorkflowRunId runId, NodeId nodeId, int attempt);

    Uni<Boolean> verifySignature(NodeExecutionResult result, String signature);
}
