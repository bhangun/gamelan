package tech.kayys.silat.engine;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.model.CallbackConfig;
import tech.kayys.silat.model.CallbackRegistration;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

@ApplicationScoped
class DefaultCallbackService implements CallbackService {

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