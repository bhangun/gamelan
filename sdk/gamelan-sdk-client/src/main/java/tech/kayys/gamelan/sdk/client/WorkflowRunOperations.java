package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

/**
 * Fluent API for workflow run operations
 */
public class WorkflowRunOperations {

    private final WorkflowRunClient client;

    WorkflowRunOperations(WorkflowRunClient client) {
        this.client = client;
    }

    /**
     * Create a new workflow run
     */
    public CreateRunBuilder create(String workflowDefinitionId) {
        return new CreateRunBuilder(client, workflowDefinitionId);
    }

    /**
     * Get a workflow run
     */
    public Uni<RunResponse> get(String runId) {
        return client.getRun(runId);
    }

    /**
     * Start a workflow run
     */
    public Uni<RunResponse> start(String runId) {
        return client.startRun(runId);
    }

    /**
     * Suspend a workflow run
     */
    public SuspendRunBuilder suspend(String runId) {
        return new SuspendRunBuilder(client, runId);
    }

    /**
     * Resume a workflow run
     */
    public ResumeRunBuilder resume(String runId) {
        return new ResumeRunBuilder(client, runId);
    }

    /**
     * Cancel a workflow run
     */
    public Uni<Void> cancel(String runId, String reason) {
        return client.cancelRun(runId, reason);
    }

    /**
     * Send signal to workflow run
     */
    public SignalBuilder signal(String runId) {
        return new SignalBuilder(client, runId);
    }

    /**
     * Get execution history
     */
    public Uni<ExecutionHistory> getHistory(String runId) {
        return client.getExecutionHistory(runId);
    }

    /**
     * Query workflow runs
     */
    public QueryRunsBuilder query() {
        return new QueryRunsBuilder(client);
    }

    /**
     * Get active runs count
     */
    public Uni<Long> getActiveCount() {
        return client.getActiveRunsCount();
    }
}
