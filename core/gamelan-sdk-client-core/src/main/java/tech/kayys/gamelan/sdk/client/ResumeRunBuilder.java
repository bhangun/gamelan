package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Fluent builder for resuming a suspended workflow.
 */
public class ResumeRunBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private final Map<String, Object> resumeData = new HashMap<>();
    private String humanTaskId;

    public ResumeRunBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    public ResumeRunBuilder data(String key, Object value) {
        this.resumeData.put(key, value);
        return this;
    }

    public ResumeRunBuilder data(Map<String, Object> data) {
        this.resumeData.putAll(data);
        return this;
    }

    public ResumeRunBuilder humanTaskId(String humanTaskId) {
        this.humanTaskId = humanTaskId;
        return this;
    }

    public Uni<RunResponse> execute() {
        return client.resumeRun(runId, resumeData, humanTaskId);
    }
}
