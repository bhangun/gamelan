package tech.kayys.gamelan.engine.impl;

import java.time.Instant;
import java.util.UUID;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenService;

@ApplicationScoped
public class DefaultExecutionTokenService implements ExecutionTokenService {

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
