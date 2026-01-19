package tech.kayys.gamelan.engine.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Compensation State - Tracks compensation progress
 */
public record CompensationState(
        List<NodeId> nodesToCompensate,
        List<NodeId> compensatedNodes,
        Instant startedAt,
        Instant completedAt,
        CompensationStatus status) {

    public static CompensationState create(List<NodeId> nodes) {
        return new CompensationState(
                new ArrayList<>(nodes),
                new ArrayList<>(),
                Instant.now(),
                null,
                CompensationStatus.PENDING);
    }

    /**
     * Mark a node as compensated
     */
    public CompensationState markNodeCompensated(NodeId nodeId) {
        if (!nodesToCompensate.contains(nodeId)) {
            throw new IllegalArgumentException("Node " + nodeId.value() + " is not in the compensation list");
        }

        List<NodeId> newCompensatedNodes = new ArrayList<>(compensatedNodes);
        newCompensatedNodes.add(nodeId);

        List<NodeId> newNodesToCompensate = new ArrayList<>(nodesToCompensate);
        newNodesToCompensate.remove(nodeId);

        CompensationStatus newStatus = newNodesToCompensate.isEmpty()
                ? CompensationStatus.COMPLETED
                : status;

        Instant newCompletedAt = newStatus == CompensationStatus.COMPLETED ? Instant.now() : completedAt;

        return new CompensationState(
                newNodesToCompensate,
                newCompensatedNodes,
                startedAt,
                newCompletedAt,
                newStatus);
    }

    /**
     * Mark compensation as failed
     */
    public CompensationState markFailed() {
        return new CompensationState(
                nodesToCompensate,
                compensatedNodes,
                startedAt,
                Instant.now(),
                CompensationStatus.FAILED);
    }

    /**
     * Check if compensation is complete
     */
    public boolean isComplete() {
        return status == CompensationStatus.COMPLETED;
    }

    /**
     * Check if compensation has failed
     */
    public boolean isFailed() {
        return status == CompensationStatus.FAILED;
    }

    /**
     * Get the next node to compensate
     */
    public NodeId getNextNodeToCompensate() {
        if (nodesToCompensate.isEmpty()) {
            return null;
        }
        return nodesToCompensate.get(0);
    }

    /**
     * Get total number of nodes to compensate
     */
    public int getTotalNodesToCompensate() {
        return nodesToCompensate.size() + compensatedNodes.size();
    }

    /**
     * Get number of nodes already compensated
     */
    public int getCompensatedCount() {
        return compensatedNodes.size();
    }

    /**
     * Get percentage of compensation completed
     */
    public double getCompletionPercentage() {
        int total = getTotalNodesToCompensate();
        if (total == 0) {
            return 100.0;
        }
        return (double) getCompensatedCount() / total * 100.0;
    }
}
