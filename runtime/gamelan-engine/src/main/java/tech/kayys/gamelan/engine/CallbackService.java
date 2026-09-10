package tech.kayys.gamelan.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface CallbackService {

    Uni<CallbackRegistration> register(
            WorkflowRunId runId,
            NodeId nodeId,
            CallbackConfig config);

    default Uni<CallbackRegistration> register(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            CallbackConfig config) {
        return register(runId, nodeId, config);
    }

    Uni<Boolean> verify(WorkflowRunId runId, String callbackToken);

    default Uni<Boolean> verify(WorkflowRunId runId, TenantId tenantId, String callbackToken) {
        return verify(runId, callbackToken);
    }
}
