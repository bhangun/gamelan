package tech.kayys.silat.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import tech.kayys.silat.execution.NodeExecutionStatus;

/**
 * Node Execution - Tracks individual node execution state
 */
public class NodeExecution {
    private final NodeId nodeId;
    private final NodeDefinition definition;
    private NodeExecutionStatus status;
    private int attempt;
    private Instant startedAt;
    private Instant completedAt;
    private Map<String, Object> output;
    private ErrorInfo lastError;

    private NodeExecution(NodeId nodeId, NodeDefinition definition) {
        this.nodeId = nodeId;
        this.definition = definition;
        this.status = NodeExecutionStatus.PENDING;
        this.attempt = 1;
        this.output = new HashMap<>();
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public NodeDefinition getDefinition() {
        return definition;
    }

    public NodeExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(NodeExecutionStatus status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Map<String, Object> getOutput() {
        return output != null ? Collections.unmodifiableMap(output) : Collections.emptyMap();
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output != null ? new HashMap<>(output) : new HashMap<>();
    }

    public ErrorInfo getLastError() {
        return lastError;
    }

    public void setLastError(ErrorInfo lastError) {
        this.lastError = lastError;
    }

    public static NodeExecution create(NodeId nodeId, NodeDefinition definition) {
        return new NodeExecution(nodeId, definition);
    }

    public void start(int attempt) {
        this.status = NodeExecutionStatus.RUNNING;
        this.attempt = attempt;
        this.startedAt = Instant.now();
    }

    public void complete(Map<String, Object> output) {
        this.status = NodeExecutionStatus.COMPLETED;
        this.output = new HashMap<>(output);
        this.completedAt = Instant.now();
    }

    public void fail(ErrorInfo error) {
        this.status = NodeExecutionStatus.FAILED;
        this.lastError = error;
        this.completedAt = Instant.now();
    }

    public void scheduleRetry(ErrorInfo error) {
        this.status = NodeExecutionStatus.RETRYING;
        this.lastError = error;
        this.attempt++;
    }

    public boolean canRetry() {
        return status == NodeExecutionStatus.RETRYING;
    }

    public boolean isCompleted() {
        return status == NodeExecutionStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == NodeExecutionStatus.FAILED;
    }

}
