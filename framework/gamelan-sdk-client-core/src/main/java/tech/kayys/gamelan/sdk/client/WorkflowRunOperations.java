package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

/**
 * Provides a fluent API for performing operations on workflow runs.
 */
public class WorkflowRunOperations {

    private final WorkflowRunClient client;

    public WorkflowRunOperations(WorkflowRunClient client) {
        this.client = client;
    }

    /**
     * Initiates the creation of a new workflow run.
     * 
     * @param workflowDefinitionId the ID of the workflow definition to run
     * @return a builder to configure and execute the run creation
     */
    public CreateRunBuilder create(String workflowDefinitionId) {
        return new CreateRunBuilder(client, workflowDefinitionId);
    }

    /**
     * Retrieves the status and details of a specific workflow run.
     * 
     * @param runId the ID of the workflow run
     * @return a Uni containing the run details
     */
    public Uni<RunResponse> get(String runId) {
        return client.getRun(runId);
    }

    /**
     * Starts execution of a previously created workflow run.
     * 
     * @param runId the ID of the workflow run
     * @return a Uni containing the updated run details
     */
    public Uni<RunResponse> start(String runId) {
        return client.startRun(runId);
    }

    /**
     * Initiates the suspension of a running workflow.
     * 
     * @param runId the ID of the workflow run
     * @return a builder to configure and execute the suspension
     */
    public SuspendRunBuilder suspend(String runId) {
        return new SuspendRunBuilder(client, runId);
    }

    /**
     * Initiates the resumption of a suspended workflow.
     * 
     * @param runId the ID of the workflow run
     * @return a builder to configure and execute the resumption
     */
    public ResumeRunBuilder resume(String runId) {
        return new ResumeRunBuilder(client, runId);
    }

    /**
     * Cancels a workflow run, stopping its execution.
     * 
     * @param runId  the ID of the workflow run
     * @param reason the reason for cancellation
     * @return a Uni representing the completion of the cancellation
     */
    public Uni<Void> cancel(String runId, String reason) {
        return client.cancelRun(runId, reason);
    }

    /**
     * Prepares to send a signal to a running workflow.
     * 
     * @param runId the ID of the workflow run
     * @return a builder to configure and send the signal
     */
    public SignalBuilder signal(String runId) {
        return new SignalBuilder(client, runId);
    }

    /**
     * Retrieves the full execution history (event log) of a workflow run.
     * 
     * @param runId the ID of the workflow run
     * @return a Uni containing the execution history
     */
    public Uni<ExecutionHistory> getHistory(String runId) {
        return client.getExecutionHistory(runId);
    }

    /**
     * Initiates a query to search for workflow runs based on various criteria.
     * 
     * @return a builder to configure and execute the query
     */
    public QueryRunsBuilder query() {
        return new QueryRunsBuilder(client);
    }

    /**
     * Retrieves the count of currently active (running or suspended) workflow runs.
     * 
     * @return a Uni containing the active run count
     */
    public Uni<Long> getActiveCount() {
        return client.getActiveRunsCount();
    }
}
