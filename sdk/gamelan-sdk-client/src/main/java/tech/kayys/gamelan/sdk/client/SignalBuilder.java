package tech.kayys.gamelan.sdk.client;

import java.util.HashMap;
import java.util.Map;

import io.smallrye.mutiny.Uni;

/**
 * Builder for sending signals
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

    public SignalBuilder name(String signalName) {
        this.signalName = signalName;
        return this;
    }

    public SignalBuilder targetNode(String nodeId) {
        this.targetNodeId = nodeId;
        return this;
    }

    public SignalBuilder payload(String key, Object value) {
        payload.put(key, value);
        return this;
    }

    public SignalBuilder payload(Map<String, Object> payload) {
        this.payload.putAll(payload);
        return this;
    }

    public Uni<Void> send() {
        return client.signal(runId, signalName, targetNodeId, payload);
    }
}
