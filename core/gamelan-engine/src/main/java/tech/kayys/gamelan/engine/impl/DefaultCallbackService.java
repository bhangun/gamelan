package tech.kayys.gamelan.engine.impl;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.callback.CallbackService;

@ApplicationScoped
public class DefaultCallbackService implements CallbackService {

    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();

    @Override
    public Uni<CallbackRegistration> register(
            WorkflowRunId runId,
            NodeId nodeId,
            CallbackConfig config) {
        String token = UUID.randomUUID().toString();
        validTokens.add(token);
        return Uni.createFrom().item(
                new CallbackRegistration(token, runId, nodeId, config.getCallbackUrl(), Instant.now()));
    }

    @Override
    public Uni<Boolean> verify(String callbackToken) {
        return Uni.createFrom().item(validTokens.contains(callbackToken));
    }
}