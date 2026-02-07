package tech.kayys.gamelan.engine.node;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import tech.kayys.gamelan.engine.error.ErrorSnapshot;

/**
 * Node Execution Snapshot - Nested in WorkflowRunEntity
 */
public class NodeExecutionSnapshot {
    private final String nodeId;
    private final String status;
    private final int attempt;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Map<String, Object> output;
    private final ErrorSnapshot error;

    @JsonCreator
    public NodeExecutionSnapshot(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("status") String status,
            @JsonProperty("attempt") int attempt,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("completedAt") Instant completedAt,
            @JsonProperty("output") Map<String, Object> output,
            @JsonProperty("error") ErrorSnapshot error) {
        this.nodeId = nodeId;
        this.status = status;
        this.attempt = attempt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.output = output;
        this.error = error;
    }

    public String nodeId() {
        return nodeId;
    }

    public String status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Map<String, Object> output() {
        return output;
    }

    public ErrorSnapshot error() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NodeExecutionSnapshot that = (NodeExecutionSnapshot) o;
        return attempt == that.attempt && Objects.equals(nodeId, that.nodeId) && Objects.equals(status, that.status)
                && Objects.equals(startedAt, that.startedAt) && Objects.equals(completedAt, that.completedAt)
                && Objects.equals(output, that.output) && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, status, attempt, startedAt, completedAt, output, error);
    }

    @Override
    public String toString() {
        return "NodeExecutionSnapshot{" +
                "nodeId='" + nodeId + '\'' +
                ", status='" + status + '\'' +
                ", attempt=" + attempt +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                ", output=" + output +
                ", error=" + error +
                '}';
    }
}
