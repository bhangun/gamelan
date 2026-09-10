package tech.kayys.gamelan.engine.repository;

import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Cursor for recovery scans.
 *
 * Repositories that can perform keyset scans should use {@code afterRunId}.
 * The page field keeps legacy offset-based repositories compatible while they
 * migrate.
 */
public record WorkflowRunRecoveryCursor(String afterRunId, int page) {

    public WorkflowRunRecoveryCursor {
        afterRunId = normalize(afterRunId);
        page = Math.max(0, page);
    }

    public static WorkflowRunRecoveryCursor start() {
        return new WorkflowRunRecoveryCursor(null, 0);
    }

    public static WorkflowRunRecoveryCursor afterRunId(String afterRunId) {
        return new WorkflowRunRecoveryCursor(afterRunId, 0);
    }

    public static WorkflowRunRecoveryCursor afterRunId(WorkflowRunId afterRunId) {
        return afterRunId(afterRunId != null ? afterRunId.value() : null);
    }

    public static WorkflowRunRecoveryCursor page(int page) {
        return new WorkflowRunRecoveryCursor(null, page);
    }

    public boolean hasAfterRunId() {
        return afterRunId != null;
    }

    public WorkflowRunRecoveryCursor nextPage() {
        return page(page + 1);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
