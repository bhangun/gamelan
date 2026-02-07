package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import java.util.HashMap;
import java.util.Map;

/**
 * Fluent builder for sending a signal to a running workflow.
 */
public class SignalBuilder {

    private final WorkflowRunClient client;
    private final String runId;
    private String signalName;
    private String targetNodeId;
    private final Map<String, Object> payload = new HashMap<>();

    public SignalBuilder(WorkflowRunClient client, String runId) {
        this.client = client;
        this.runId = runId;
    }

    public SignalBuilder name(String signalName) {
        this.signalName = signalName;
        return this;
    }

    public SignalBuilder targetNode(String targetNodeId) {
        this.targetNodeId = targetNodeId;
        return this;
    }

    public SignalBuilder data(String key, Object value) {
        this.payload.put(key, value);
        return this;
    }

    public SignalBuilder payload(Map<String, Object> payload) {
        this.payload.putAll(payload);
        return this;
    }

    public Uni<Void> execute() {
        if (signalName == null || signalName.trim().isEmpty()) {
            throw new IllegalArgumentException("Signal name cannot be null or empty");
        }
        return client.signal(runId, signalName, targetNodeId, payload);
    }
}
