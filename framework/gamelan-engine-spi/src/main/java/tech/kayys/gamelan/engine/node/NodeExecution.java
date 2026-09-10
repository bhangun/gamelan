package tech.kayys.gamelan.engine.node;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

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
    private Instant retryAt;
    private Map<String, Object> output;
    private ErrorInfo lastError;

    private NodeExecution(NodeId nodeId, NodeDefinition definition) {
        this.nodeId = nodeId;
        this.definition = definition;
        this.status = NodeExecutionStatus.PENDING;
        this.attempt = 1;
        this.output = ExecutionPayloads.mutableMap(null);
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

    public Instant getRetryAt() {
        return retryAt;
    }

    public void setRetryAt(Instant retryAt) {
        this.retryAt = retryAt;
    }

    public Map<String, Object> getOutput() {
        return output != null ? Collections.unmodifiableMap(output) : Collections.emptyMap();
    }

    public void setOutput(Map<String, Object> output) {
        this.output = ExecutionPayloads.mutableMap(output);
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

    public static NodeExecution copyOf(NodeExecution source) {
        Objects.requireNonNull(source, "NodeExecution cannot be null");

        NodeExecution copy = new NodeExecution(source.nodeId, source.definition);
        copy.status = source.status;
        copy.attempt = source.attempt;
        copy.startedAt = source.startedAt;
        copy.completedAt = source.completedAt;
        copy.retryAt = source.retryAt;
        copy.output = ExecutionPayloads.mutableMap(source.output);
        copy.lastError = source.lastError;
        return copy;
    }

    public void start(int attempt) {
        this.status = NodeExecutionStatus.RUNNING;
        this.attempt = attempt;
        this.retryAt = null;
        this.startedAt = Instant.now();
    }

    public void complete(Map<String, Object> output) {
        this.status = NodeExecutionStatus.COMPLETED;
        this.output = ExecutionPayloads.mutableMap(output);
        this.retryAt = null;
        this.completedAt = Instant.now();
    }

    public void fail(ErrorInfo error) {
        this.status = NodeExecutionStatus.FAILED;
        this.lastError = error;
        this.retryAt = null;
        this.completedAt = Instant.now();
    }

    public void scheduleRetry(ErrorInfo error) {
        scheduleRetry(error, Instant.now());
    }

    public void scheduleRetry(ErrorInfo error, Instant retryAt) {
        this.status = NodeExecutionStatus.RETRYING;
        this.lastError = error;
        this.retryAt = retryAt;
        this.attempt++;
    }

    public boolean canRetry() {
        return status == NodeExecutionStatus.RETRYING;
    }

    public boolean isRetryDue(Instant now) {
        if (!canRetry()) {
            return false;
        }
        Instant effectiveNow = now != null ? now : Instant.now();
        return retryAt == null || !retryAt.isAfter(effectiveNow);
    }

    public boolean isCompleted() {
        return status == NodeExecutionStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == NodeExecutionStatus.FAILED;
    }

}
