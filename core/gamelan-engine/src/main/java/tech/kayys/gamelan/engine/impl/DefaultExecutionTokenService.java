package tech.kayys.gamelan.engine.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenService;

@ApplicationScoped
public class DefaultExecutionTokenService implements ExecutionTokenService {

    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(5);

    @Inject
    WorkflowRunRepository runRepository;

    @Override
    public Uni<ExecutionToken> issue(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt) {
        return issue(runId, null, nodeId, attempt);
    }

    @Override
    public Uni<ExecutionToken> issue(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt) {
        ExecutionToken token = ExecutionToken.create(runId, tenantId, nodeId, attempt, DEFAULT_TOKEN_TTL);
        return runRepository.storeToken(token).replaceWith(token);
    }

    @Override
    public Uni<Boolean> verifySignature(
            NodeExecutionResult result,
            String signature) {
        if (result == null || result.executionToken() == null || signature == null || signature.isBlank()) {
            return Uni.createFrom().item(false);
        }

        ExecutionToken token = result.executionToken();
        if (token.isExpired() || !matchesResult(token, result) || !constantTimeEquals(token.value(), signature)) {
            return Uni.createFrom().item(false);
        }

        return runRepository.validateToken(token);
    }

    private boolean matchesResult(ExecutionToken token, NodeExecutionResult result) {
        return token.runId().equals(result.runId())
                && token.nodeId().equals(result.nodeId())
                && token.attempt() == result.attempt();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
