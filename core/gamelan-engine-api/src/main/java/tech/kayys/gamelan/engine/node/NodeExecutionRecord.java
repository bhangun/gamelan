package tech.kayys.gamelan.engine.node;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import tech.kayys.gamelan.engine.execution.ExecutionError;

/**
 * Record of a node execution for history tracking.
 */
public class NodeExecutionRecord {
    private final String nodeId;
    private final NodeExecutionStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Duration duration;
    private final Map<String, Object> inputs;
    private final Map<String, Object> outputs;
    private final ExecutionError error;
    private final Map<String, Object> metadata;
    private final int attempt;

    public NodeExecutionRecord(String nodeId, NodeExecutionStatus status, Instant startedAt,
            Instant completedAt, Duration duration, Map<String, Object> inputs,
            Map<String, Object> outputs, ExecutionError error,
            Map<String, Object> metadata, int attempt) {
        this.nodeId = nodeId;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.duration = duration;
        this.inputs = inputs;
        this.outputs = outputs;
        this.error = error;
        this.metadata = metadata;
        this.attempt = attempt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getNodeId() {
        return nodeId;
    }

    public NodeExecutionStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Duration getDuration() {
        return duration;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public Map<String, Object> getOutputs() {
        return outputs;
    }

    public ExecutionError getError() {
        return error;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public int getAttempt() {
        return attempt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NodeExecutionRecord that = (NodeExecutionRecord) o;
        return attempt == that.attempt &&
                Objects.equals(nodeId, that.nodeId) &&
                status == that.status &&
                Objects.equals(startedAt, that.startedAt) &&
                Objects.equals(completedAt, that.completedAt) &&
                Objects.equals(duration, that.duration) &&
                Objects.equals(inputs, that.inputs) &&
                Objects.equals(outputs, that.outputs) &&
                Objects.equals(error, that.error) &&
                Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, status, startedAt, completedAt, duration, inputs, outputs, error, metadata,
                attempt);
    }

    public static class Builder {
        private String nodeId;
        private NodeExecutionStatus status;
        private Instant startedAt;
        private Instant completedAt;
        private Duration duration;
        private Map<String, Object> inputs;
        private Map<String, Object> outputs;
        private ExecutionError error;
        private Map<String, Object> metadata;
        private int attempt;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder status(NodeExecutionStatus status) {
            this.status = status;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder inputs(Map<String, Object> inputs) {
            this.inputs = inputs;
            return this;
        }

        public Builder outputs(Map<String, Object> outputs) {
            this.outputs = outputs;
            return this;
        }

        public Builder error(ExecutionError error) {
            this.error = error;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        public NodeExecutionRecord build() {
            return new NodeExecutionRecord(nodeId, status, startedAt, completedAt, duration, inputs, outputs, error,
                    metadata, attempt);
        }
    }
}
