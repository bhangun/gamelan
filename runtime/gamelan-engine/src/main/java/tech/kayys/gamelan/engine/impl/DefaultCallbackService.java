package tech.kayys.gamelan.engine.impl;

import java.time.Instant;
import java.util.Objects;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.callback.CallbackService;
import tech.kayys.gamelan.engine.execution.BearerTokens;
import tech.kayys.gamelan.engine.tenant.TenantId;

@ApplicationScoped
public class DefaultCallbackService implements CallbackService {

    @Inject
    WorkflowRunRepository runRepository;

    public DefaultCallbackService() {
    }

    public DefaultCallbackService(WorkflowRunRepository runRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository cannot be null");
    }

    @Override
    public Uni<CallbackRegistration> register(
            WorkflowRunId runId,
            NodeId nodeId,
            CallbackConfig config) {
        return register(runId, null, nodeId, config);
    }

    @Override
    public Uni<CallbackRegistration> register(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            CallbackConfig config) {
        Objects.requireNonNull(runRepository, "runRepository cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        String token = BearerTokens.randomUrlSafe();
        CallbackRegistration registration = new CallbackRegistration(
                token,
                runId,
                tenantId,
                nodeId,
                config.getCallbackUrl(),
                Instant.now().plus(config.getTimeout()));
        return runRepository.storeCallback(registration).replaceWith(registration);
    }

    @Override
    public Uni<Boolean> verify(WorkflowRunId runId, String callbackToken) {
        if (runId == null || callbackToken == null || callbackToken.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return runRepository.validateCallback(runId, callbackToken);
    }

    @Override
    public Uni<Boolean> verify(WorkflowRunId runId, TenantId tenantId, String callbackToken) {
        if (runId == null || callbackToken == null || callbackToken.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return runRepository.validateCallback(runId, tenantId, callbackToken);
    }
}
