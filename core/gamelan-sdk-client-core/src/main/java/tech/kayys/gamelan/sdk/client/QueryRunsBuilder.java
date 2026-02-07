package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import java.util.List;

/**
 * Fluent builder for querying workflow runs based on filters.
 */
public class QueryRunsBuilder {

    private final WorkflowRunClient client;
    private String workflowId;
    private String status;
    private int page = 0;
    private int size = 20;

    public QueryRunsBuilder(WorkflowRunClient client) {
        this.client = client;
    }

    public QueryRunsBuilder workflowId(String workflowId) {
        this.workflowId = workflowId;
        return this;
    }

    public QueryRunsBuilder status(String status) {
        this.status = status;
        return this;
    }

    public QueryRunsBuilder page(int page) {
        this.page = page;
        return this;
    }

    public QueryRunsBuilder size(int size) {
        this.size = size;
        return this;
    }

    public Uni<List<RunResponse>> execute() {
        return client.queryRuns(workflowId, status, page, size);
    }
}
