package tech.kayys.gamelan.sdk.client;

import java.util.HashMap;
import java.util.Map;

import io.smallrye.mutiny.Uni;

/**
 * Fluent builder for sending a signal to a running workflow.
 * Instances of this builder are obtained via
 * {@link WorkflowRunOperations#signal(String)}.
 */
public class SignalBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private String signalName;
    private String targetNodeId;
    private final Map<String, Object> payload = new HashMap<>();

    SignalBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    /**
     * Sets the name of the signal to send.
     * 
     * @param signalName the signal name
     * @return this builder
     */
    public SignalBuilder name(String signalName) {
        this.signalName = signalName;
        return this;
    }

    /**
     * Optionally specifies a target node ID that should receive the signal.
     * 
     * @param nodeId the target node ID
     * @return this builder
     */
    public SignalBuilder targetNode(String nodeId) {
        this.targetNodeId = nodeId;
        return this;
    }

    /**
     * Adds a single data item to the signal payload.
     * 
     * @param key   the data key
     * @param value the data value
     * @return this builder
     */
    public SignalBuilder payload(String key, Object value) {
        payload.put(key, value);
        return this;
    }

    /**
     * Adds multiple data items to the signal payload.
     * 
     * @param payload a map of payload data
     * @return this builder
     */
    public SignalBuilder payload(Map<String, Object> payload) {
        this.payload.putAll(payload);
        return this;
    }

    /**
     * Sends the signal to the workflow run.
     * 
     * @return a Uni representing the completion of the signal operation
     */
    public Uni<Void> send() {
        return client.signal(runId, signalName, targetNodeId, payload);
    }
}
