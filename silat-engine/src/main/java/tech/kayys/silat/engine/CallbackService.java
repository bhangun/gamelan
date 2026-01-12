package tech.kayys.silat.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.model.CallbackConfig;
import tech.kayys.silat.model.CallbackRegistration;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

interface CallbackService {

    Uni<CallbackRegistration> register(
            WorkflowRunId runId,
            NodeId nodeId,
            CallbackConfig config);

    Uni<Boolean> verify(String callbackToken);
}