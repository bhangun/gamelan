package tech.kayys.gamelan.sdk.client;

import java.util.HashMap;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;

/**
 * Fluent builder for resuming a suspended workflow run.
 * Instances of this builder are obtained via
 * {@link WorkflowRunOperations#resume(String)}.
 */
public class ResumeRunBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private final Map<String, Object> resumeData = new HashMap<>();
    private String humanTaskId;

    ResumeRunBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    /**
     * Adds a single data item to the resumption payload.
     * 
     * @param key   the data key
     * @param value the data value
     * @return this builder
     */
    public ResumeRunBuilder data(String key, Object value) {
        resumeData.put(key, value);
        return this;
    }

    /**
     * Adds multiple data items to the resumption payload.
     * 
     * @param data a map of resumption data
     * @return this builder
     */
    public ResumeRunBuilder data(Map<String, Object> data) {
        this.resumeData.putAll(data);
        return this;
    }

    /**
     * Sets the human task ID associated with this resumption (if applicable).
     * 
     * @param taskId the human task ID
     * @return this builder
     */
    public ResumeRunBuilder humanTaskId(String taskId) {
        this.humanTaskId = taskId;
        return this;
    }

    /**
     * Executes the resumption request.
     * 
     * @return a Uni containing the updated run details
     */
    public Uni<RunResponse> execute() {
        return client.resumeRun(runId, resumeData, humanTaskId);
    }
}
