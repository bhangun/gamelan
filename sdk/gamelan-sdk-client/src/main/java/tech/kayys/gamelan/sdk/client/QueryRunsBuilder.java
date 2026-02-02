package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import java.util.List;

/**
 * Fluent builder for querying workflow runs based on filters.
 * Instances of this builder are obtained via
 * {@link WorkflowRunOperations#query()}.
 */
public class QueryRunsBuilder {

    private final WorkflowRunClient client;
    private String workflowId;
    private String status;
    private int page = 0;
    private int size = 20;

    QueryRunsBuilder(WorkflowRunClient client) {
        this.client = client;
    }

    /**
     * Filters runs by workflow definition ID.
     * 
     * @param workflowId the workflow ID
     * @return this builder
     */
    public QueryRunsBuilder workflowId(String workflowId) {
        this.workflowId = workflowId;
        return this;
    }

    /**
     * Filters runs by status (e.g., "RUNNING", "COMPLETED", "FAILED").
     * 
     * @param status the status string
     * @return this builder
     */
    public QueryRunsBuilder status(String status) {
        this.status = status;
        return this;
    }

    /**
     * Sets the page number for pagination.
     * 
     * @param page the page number (0-indexed)
     * @return this builder
     */
    public QueryRunsBuilder page(int page) {
        this.page = page;
        return this;
    }

    /**
     * Sets the page size for pagination.
     * 
     * @param size the maximum number of results per page
     * @return this builder
     */
    public QueryRunsBuilder size(int size) {
        this.size = size;
        return this;
    }

    /**
     * Executes the query.
     * 
     * @return a Uni containing a list of matching workflow runs
     */
    public Uni<List<RunResponse>> execute() {
        return client.queryRuns(workflowId, status, page, size);
    }
}
