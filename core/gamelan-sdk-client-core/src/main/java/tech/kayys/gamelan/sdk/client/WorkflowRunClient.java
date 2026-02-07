package tech.kayys.gamelan.sdk.client;

import java.util.Map;
import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

/**
 * Interface for workflow run operations.
 * This interface is transport-agnostic, with implementations for REST, gRPC,
 * and Local.
 */
public interface WorkflowRunClient extends AutoCloseable {
    /**
     * Creates a new workflow run based on the provided request.
     * 
     * @param request the run creation details
     * @return a Uni containing the created run details
     */
    Uni<RunResponse> createRun(CreateRunRequest request);

    /**
     * Retrieves the current state of a workflow run.
     * 
     * @param runId the unique ID of the workflow run
     * @return a Uni containing the run details
     */
    Uni<RunResponse> getRun(String runId);

    /**
     * Starts execution of a previously created workflow run.
     * 
     * @param runId the unique ID of the workflow run
     * @return a Uni containing the updated run details
     */
    Uni<RunResponse> startRun(String runId);

    /**
     * Suspends a running workflow execution.
     * 
     * @param runId           the unique ID of the workflow run
     * @param reason          the reason for suspension
     * @param waitingOnNodeId optionally, the node ID that the workflow is waiting
     *                        on
     * @return a Uni containing the updated run details
     */
    Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId);

    /**
     * Resumes a suspended workflow execution.
     * 
     * @param runId       the unique ID of the workflow run
     * @param resumeData  data to pass back into the workflow upon resumption
     * @param humanTaskId optionally, the ID of the human task being completed
     * @return a Uni containing the updated run details
     */
    Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId);

    /**
     * Cancels a workflow run, stopping all execution.
     * 
     * @param runId  the unique ID of the workflow run
     * @param reason the reason for cancellation
     * @return a Uni representing completion
     */
    Uni<Void> cancelRun(String runId, String reason);

    /**
     * Sends a signal to a running workflow.
     * 
     * @param runId        the unique ID of the workflow run
     * @param signalName   the name of the signal
     * @param targetNodeId optionally, the target node that should receive the
     *                     signal
     * @param payload      the signal data
     * @return a Uni representing completion
     */
    Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload);

    /**
     * Retrieves the execution history (event log) for a workflow run.
     * 
     * @param runId the unique ID of the workflow run
     * @return a Uni containing the history
     */
    Uni<ExecutionHistory> getExecutionHistory(String runId);

    /**
     * Queries workflow runs based on filtering criteria.
     * 
     * @param workflowId filter by workflow definition ID
     * @param status     filter by run status
     * @param page       page number for pagination
     * @param size       page size for pagination
     * @return a Uni containing a list of matching runs
     */
    Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size);

    /**
     * Returns the count of currently active runs.
     * 
     * @return a Uni containing the count
     */
    Uni<Long> getActiveRunsCount();

    @Override
    void close();
}
