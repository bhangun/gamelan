package tech.kayys.silat.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.model.RunResponse;
import tech.kayys.silat.model.CreateRunRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Builder for creating workflow runs
 */
public class CreateRunBuilder {

    private final WorkflowRunClient client;
    private final String workflowDefinitionId;
    private final Map<String, Object> inputs = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private String workflowVersion = "1.0.0";
    private String correlationId;
    private boolean autoStart = false;

    CreateRunBuilder(WorkflowRunClient client, String workflowDefinitionId) {
        this.client = client;
        this.workflowDefinitionId = workflowDefinitionId;
    }

    public CreateRunBuilder version(String version) {
        this.workflowVersion = version;
        return this;
    }

    public CreateRunBuilder input(String key, Object value) {
        inputs.put(key, value);
        return this;
    }

    public CreateRunBuilder inputs(Map<String, Object> inputs) {
        this.inputs.putAll(inputs);
        return this;
    }

    public CreateRunBuilder correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public CreateRunBuilder autoStart(boolean autoStart) {
        this.autoStart = autoStart;
        return this;
    }

    public CreateRunBuilder label(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Label key cannot be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("Label value cannot be null");
        }
        this.labels.put(key, value);
        return this;
    }

    public CreateRunBuilder labels(Map<String, String> labels) {
        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                label(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    /**
     * Get the labels map for validation or debugging purposes
     */
    public Map<String, String> getLabels() {
        return new HashMap<>(labels);
    }

    /**
     * Execute and return the created run
     */
    public Uni<RunResponse> execute() {
        CreateRunRequest request = new CreateRunRequest(
                workflowDefinitionId,
                workflowVersion,
                inputs,
                correlationId,
                autoStart);
        return client.createRun(request);
    }

    /**
     * Execute and immediately start the run
     */
    public Uni<RunResponse> executeAndStart() {
        this.autoStart = true;
        return execute();
    }
}
