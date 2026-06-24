package tech.kayys.gamelan.engine.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Commits accepted workflow signals in an audit-first order.
 */
@ApplicationScoped
public class WorkflowSignalCommitService {

    @Inject
    WorkflowRunRepository runRepository;

    @Inject
    ExecutionHistoryRepository historyRepository;

    public WorkflowSignalCommitService() {
    }

    WorkflowSignalCommitService(
            WorkflowRunRepository runRepository,
            ExecutionHistoryRepository historyRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
    }

    public Uni<Void> commitAcceptedSignal(
            WorkflowRun run,
            WorkflowRunId runId,
            TenantId tenantId,
            Signal signal,
            String idempotencyKey,
            Map<String, Object> metadata) {
        WorkflowRun safeRun = Objects.requireNonNull(run, "run");
        WorkflowRunId safeRunId = Objects.requireNonNull(runId, "runId");
        TenantId safeTenantId = Objects.requireNonNull(tenantId, "tenantId");
        Signal safeSignal = Objects.requireNonNull(signal, "signal");
        String safeIdempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Map<String, Object> metadataSnapshot = metadata != null
                ? new HashMap<>(metadata)
                : new HashMap<>();

        return historyRepository.appendSignalReceivedAudit(
                safeRunId,
                safeTenantId,
                safeIdempotencyKey,
                safeSignal.name(),
                metadataSnapshot)
                .chain(() -> {
                    boolean mutated = safeRun.applySignal(safeSignal);
                    return mutated
                            ? runRepository.update(safeRun).replaceWithVoid()
                            : Uni.createFrom().voidItem();
                })
                .chain(() -> historyRepository.markExternalSignalProcessed(
                        safeRunId,
                        safeTenantId,
                        safeIdempotencyKey))
                .replaceWithVoid();
    }
}
