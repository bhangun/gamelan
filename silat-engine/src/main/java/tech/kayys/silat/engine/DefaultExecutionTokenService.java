package tech.kayys.silat.engine;

import java.time.Instant;
import java.util.UUID;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

@ApplicationScoped
class DefaultExecutionTokenService implements ExecutionTokenService {

    @Override
    public Uni<ExecutionToken> issue(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt) {
        return Uni.createFrom().item(
                new ExecutionToken(
                        UUID.randomUUID().toString(),
                        runId,
                        nodeId,
                        attempt,
                        Instant.now().plusSeconds(300)));
    }

    @Override
    public Uni<Boolean> verifySignature(
            NodeExecutionResult result,
            String signature) {
        return Uni.createFrom().item(signature != null && !signature.isBlank());
    }
}
