package tech.kayys.gamelan.engine.impl;

import java.util.List;
import java.util.Objects;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Commits domain events raised by a workflow run to the execution history and
 * persists the run version only after the history append succeeds.
 */
@ApplicationScoped
public class WorkflowRunCommitService {

    @Inject
    WorkflowRunRepository runRepository;

    @Inject
    ExecutionHistoryRepository historyRepository;

    public WorkflowRunCommitService() {
    }

    WorkflowRunCommitService(
            WorkflowRunRepository runRepository,
            ExecutionHistoryRepository historyRepository) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
    }

    public Uni<Void> commitEvents(
            WorkflowRun run,
            WorkflowRunId runId,
            TenantId tenantId,
            List<ExecutionEvent> events) {
        if (events == null || events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        WorkflowRun safeRun = Objects.requireNonNull(run, "run");
        WorkflowRunId safeRunId = Objects.requireNonNull(runId, "runId");
        TenantId safeTenantId = Objects.requireNonNull(tenantId, "tenantId");
        List<ExecutionEvent> eventSnapshot = List.copyOf(events);

        return historyRepository.appendEvents(safeRunId, safeTenantId, eventSnapshot)
                .invoke(() -> safeRun.markEventsAsCommitted(eventSnapshot))
                .chain(() -> runRepository.update(safeRun).replaceWithVoid());
    }
}
