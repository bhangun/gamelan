package tech.kayys.gamelan.engine.callback;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface CallbackService {

    Uni<CallbackRegistration> register(
            WorkflowRunId runId,
            NodeId nodeId,
            CallbackConfig config);

    Uni<Boolean> verify(String callbackToken);
}