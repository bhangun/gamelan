package tech.kayys.gamelan.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface ExecutionTokenService {

    Uni<ExecutionToken> issue(WorkflowRunId runId, NodeId nodeId, int attempt);

    default Uni<ExecutionToken> issue(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        return issue(runId, nodeId, attempt);
    }

    Uni<Boolean> verifySignature(NodeExecutionResult result, String signature);

    default Uni<Boolean> verifySignature(NodeExecutionResult result, TenantId expectedTenantId, String signature) {
        if (expectedTenantId != null
                && result != null
                && result.executionToken() != null
                && result.executionToken().tenantId() != null
                && !expectedTenantId.equals(result.executionToken().tenantId())) {
            return Uni.createFrom().item(false);
        }
        return verifySignature(result, signature);
    }
}
