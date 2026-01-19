package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;

/**
 * Builder for suspending runs
 */
public class SuspendRunBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private String reason;
    private String waitingOnNodeId;

    SuspendRunBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    public SuspendRunBuilder reason(String reason) {
        this.reason = reason;
        return this;
    }

    public SuspendRunBuilder waitingOnNode(String nodeId) {
        this.waitingOnNodeId = nodeId;
        return this;
    }

    public Uni<RunResponse> execute() {
        return client.suspendRun(runId, reason, waitingOnNodeId);
    }
}
