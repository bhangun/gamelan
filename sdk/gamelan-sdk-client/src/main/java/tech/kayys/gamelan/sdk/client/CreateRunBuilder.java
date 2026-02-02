package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Fluent builder for creating and optionally starting a workflow run.
 * Instances of this builder are obtained via
 * {@link WorkflowRunOperations#create(String)}.
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

    /**
     * Sets the version of the workflow definition to use.
     * 
     * @param version the workflow version (default: "1.0.0")
     * @return this builder
     */
    public CreateRunBuilder version(String version) {
        this.workflowVersion = version;
        return this;
    }

    /**
     * Sets a single input parameter for the workflow run.
     * 
     * @param key   the input key
     * @param value the input value
     * @return this builder
     */
    public CreateRunBuilder input(String key, Object value) {
        inputs.put(key, value);
        return this;
    }

    /**
     * Sets multiple input parameters for the workflow run.
     * 
     * @param inputs a map of input parameters
     * @return this builder
     */
    public CreateRunBuilder inputs(Map<String, Object> inputs) {
        this.inputs.putAll(inputs);
        return this;
    }

    /**
     * Sets a correlation ID for tracking the workflow run across systems.
     * 
     * @param correlationId the correlation ID
     * @return this builder
     */
    public CreateRunBuilder correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    /**
     * Configures whether the workflow run should start immediately after creation.
     * 
     * @param autoStart true to start immediately, false otherwise
     * @return this builder
     */
    public CreateRunBuilder autoStart(boolean autoStart) {
        this.autoStart = autoStart;
        return this;
    }

    /**
     * Adds a metadata label to the workflow run.
     * 
     * @param key   the label key
     * @param value the label value
     * @return this builder
     * @throws IllegalArgumentException if key or value is invalid
     */
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

    /**
     * Adds multiple metadata labels to the workflow run.
     * 
     * @param labels a map of labels
     * @return this builder
     */
    public CreateRunBuilder labels(Map<String, String> labels) {
        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                label(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    /**
     * Executes the run creation request.
     * 
     * @return a Uni containing the response from the Gamelan service
     */
    public Uni<RunResponse> execute() {
        CreateRunRequest request = new CreateRunRequest(
                workflowDefinitionId,
                workflowVersion,
                inputs,
                correlationId,
                autoStart);
        // Note: labels are not yet supported in CreateRunRequest DTO based on engine
        // source,
        // but we keep them here for future integration.
        return client.createRun(request);
    }

    /**
     * Executes the run creation request and ensures it is started immediately.
     * 
     * @return a Uni containing the response from the Gamelan service
     */
    public Uni<RunResponse> executeAndStart() {
        this.autoStart = true;
        return execute();
    }
}
