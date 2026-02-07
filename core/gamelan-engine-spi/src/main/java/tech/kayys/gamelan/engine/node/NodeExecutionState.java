package tech.kayys.gamelan.engine.node;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.error.ErrorInfo;

/**
 * Node Execution State - Tracks individual node execution
 */
public record NodeExecutionState(
        NodeId nodeId,
        NodeExecutionStatus status,
        int attempt,
        Instant startedAt,
        Instant completedAt,
        Map<String, Object> output,
        ErrorInfo error) {
    public NodeExecutionState {
        output = output != null ? Map.copyOf(output) : Map.of();
    }

    public boolean isCompleted() {
        return status == NodeExecutionStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == NodeExecutionStatus.FAILED;
    }

    public Duration getDuration() {
        if (startedAt != null && completedAt != null) {
            return Duration.between(startedAt, completedAt);
        }
        return Duration.ZERO;
    }
}
