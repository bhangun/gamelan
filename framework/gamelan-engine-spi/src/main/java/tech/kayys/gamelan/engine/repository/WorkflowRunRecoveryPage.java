package tech.kayys.gamelan.engine.repository;

import java.util.List;

import tech.kayys.gamelan.engine.workflow.WorkflowRun;

/**
 * One page of workflow runs scanned for recovery.
 */
public record WorkflowRunRecoveryPage(
        List<WorkflowRun> runs,
        WorkflowRunRecoveryCursor nextCursor,
        boolean hasMore) {

    public WorkflowRunRecoveryPage {
        runs = runs != null ? List.copyOf(runs) : List.of();
        nextCursor = nextCursor != null ? nextCursor : WorkflowRunRecoveryCursor.start();
    }

    public static WorkflowRunRecoveryPage keyset(List<WorkflowRun> runs, int requestedSize) {
        List<WorkflowRun> fetchedRuns = runs != null ? List.copyOf(runs) : List.of();
        boolean hasMore = requestedSize > 0 && fetchedRuns.size() > requestedSize;
        List<WorkflowRun> pageRuns = hasMore
                ? List.copyOf(fetchedRuns.subList(0, requestedSize))
                : fetchedRuns;
        WorkflowRunRecoveryCursor nextCursor = pageRuns.isEmpty()
                ? WorkflowRunRecoveryCursor.start()
                : WorkflowRunRecoveryCursor.afterRunId(pageRuns.getLast().getId());
        return new WorkflowRunRecoveryPage(pageRuns, nextCursor, hasMore);
    }

    public static WorkflowRunRecoveryPage offset(List<WorkflowRun> runs, WorkflowRunRecoveryCursor cursor, int requestedSize) {
        List<WorkflowRun> safeRuns = runs != null ? List.copyOf(runs) : List.of();
        WorkflowRunRecoveryCursor safeCursor = cursor != null ? cursor : WorkflowRunRecoveryCursor.start();
        boolean hasMore = requestedSize > 0 && safeRuns.size() >= requestedSize;
        return new WorkflowRunRecoveryPage(
                safeRuns,
                hasMore ? safeCursor.nextPage() : safeCursor,
                hasMore);
    }
}
