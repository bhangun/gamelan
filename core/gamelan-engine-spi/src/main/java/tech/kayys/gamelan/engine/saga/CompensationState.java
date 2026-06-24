package tech.kayys.gamelan.engine.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Compensation State - Tracks compensation progress
 */
public record CompensationState(
        List<NodeId> nodesToCompensate,
        List<CompensationClaim> compensationClaims,
        List<NodeId> compensatedNodes,
        Instant startedAt,
        Instant completedAt,
        CompensationStatus status) {
    public CompensationState(
            List<NodeId> nodesToCompensate,
            List<NodeId> compensatedNodes,
            Instant startedAt,
            Instant completedAt,
            CompensationStatus status) {
        this(nodesToCompensate, List.of(), compensatedNodes, startedAt, completedAt, status);
    }

    public CompensationState {
        startedAt = startedAt != null ? startedAt : Instant.now();
        status = status != null ? status : CompensationStatus.PENDING;
        compensatedNodes = distinctNodes(compensatedNodes);
        compensationClaims = normalizeClaims(compensationClaims, compensatedNodes);
        nodesToCompensate = withoutCompensatedNodes(distinctNodes(nodesToCompensate), compensatedNodes);
        if (status == CompensationStatus.COMPLETED) {
            compensatedNodes = appendDistinct(compensatedNodes, nodesToCompensate);
            compensationClaims = List.of();
            nodesToCompensate = List.of();
            completedAt = completedAt != null ? completedAt : startedAt;
        } else if (status == CompensationStatus.FAILED) {
            compensationClaims = List.of();
            completedAt = completedAt != null ? completedAt : startedAt;
        } else {
            completedAt = null;
        }
    }

    public static CompensationState create(List<NodeId> nodes) {
        return new CompensationState(
                nodes,
                List.of(),
                List.of(),
                Instant.now(),
                null,
                CompensationStatus.PENDING);
    }

    private static List<NodeId> distinctNodes(List<NodeId> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<NodeId> uniqueNodes = new LinkedHashSet<>();
        nodes.stream()
                .filter(Objects::nonNull)
                .forEach(uniqueNodes::add);
        return List.copyOf(uniqueNodes);
    }

    private static List<NodeId> withoutCompensatedNodes(
            List<NodeId> nodesToCompensate,
            List<NodeId> compensatedNodes) {
        if (nodesToCompensate.isEmpty() || compensatedNodes.isEmpty()) {
            return nodesToCompensate;
        }
        return nodesToCompensate.stream()
                .filter(nodeId -> !compensatedNodes.contains(nodeId))
                .toList();
    }

    private static List<NodeId> appendDistinct(List<NodeId> existing, List<NodeId> additional) {
        if (additional.isEmpty()) {
            return existing;
        }
        LinkedHashSet<NodeId> nodes = new LinkedHashSet<>(existing);
        nodes.addAll(additional);
        return List.copyOf(nodes);
    }

    private static List<CompensationClaim> normalizeClaims(
            List<CompensationClaim> claims,
            List<NodeId> compensatedNodes) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }

        List<CompensationClaim> normalized = new ArrayList<>();
        for (CompensationClaim claim : claims) {
            if (claim == null || compensatedNodes.contains(claim.nodeId())) {
                continue;
            }
            normalized.removeIf(existing -> existing.nodeId().equals(claim.nodeId()));
            normalized.add(claim);
        }
        return List.copyOf(normalized);
    }

    /**
     * Claim a node before executing rollback side effects.
     */
    public CompensationState claimNode(NodeId nodeId, String claimId, Instant now, Duration lease) {
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        rejectTerminalTransition("claim node compensation");
        if (!nodesToCompensate.contains(nodeId)) {
            throw new GamelanException(
                    ErrorCode.RUN_COMPENSATION_NOT_READY,
                    "Node " + nodeId.value() + " is not in the compensation list");
        }

        Instant claimedAt = now != null ? now : Instant.now();
        Duration safeLease = lease != null && !lease.isZero() && !lease.isNegative()
                ? lease
                : Duration.ofMinutes(1);
        List<CompensationClaim> newClaims = new ArrayList<>(activeClaims(claimedAt));
        newClaims.removeIf(claim -> claim.nodeId().equals(nodeId));
        newClaims.add(new CompensationClaim(nodeId, claimId, claimedAt, claimedAt.plus(safeLease)));

        return new CompensationState(
                nodesToCompensate,
                newClaims,
                compensatedNodes,
                startedAt,
                completedAt,
                CompensationStatus.IN_PROGRESS);
    }

    public CompensationState releaseNodeClaim(NodeId nodeId, String claimId) {
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        if (compensationClaims.isEmpty()) {
            return this;
        }

        List<CompensationClaim> newClaims = compensationClaims.stream()
                .filter(claim -> !claim.nodeId().equals(nodeId) || !Objects.equals(claim.claimId(), claimId))
                .toList();
        if (newClaims.size() == compensationClaims.size()) {
            return this;
        }

        CompensationStatus newStatus = newClaims.isEmpty() ? CompensationStatus.PENDING : status;
        return new CompensationState(
                nodesToCompensate,
                newClaims,
                compensatedNodes,
                startedAt,
                completedAt,
                newStatus);
    }

    public boolean hasActiveClaim(NodeId nodeId, Instant now) {
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        Instant referenceTime = now != null ? now : Instant.now();
        return compensationClaims.stream()
                .anyMatch(claim -> claim.nodeId().equals(nodeId) && claim.isActive(referenceTime));
    }

    private List<CompensationClaim> activeClaims(Instant now) {
        Instant referenceTime = now != null ? now : Instant.now();
        return compensationClaims.stream()
                .filter(claim -> claim.isActive(referenceTime))
                .toList();
    }

    /**
     * Mark a node as compensated
     */
    public CompensationState markNodeCompensated(NodeId nodeId) {
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        if (compensatedNodes.contains(nodeId)) {
            return this;
        }
        rejectTerminalTransition("mark node compensated");
        if (!nodesToCompensate.contains(nodeId)) {
            throw new GamelanException(
                    ErrorCode.RUN_COMPENSATION_NOT_READY,
                    "Node " + nodeId.value() + " is not in the compensation list");
        }

        List<NodeId> newCompensatedNodes = new ArrayList<>(compensatedNodes);
        newCompensatedNodes.add(nodeId);

        List<NodeId> newNodesToCompensate = new ArrayList<>(nodesToCompensate);
        newNodesToCompensate.remove(nodeId);
        List<CompensationClaim> newClaims = compensationClaims.stream()
                .filter(claim -> !claim.nodeId().equals(nodeId))
                .toList();

        CompensationStatus newStatus = newNodesToCompensate.isEmpty()
                ? CompensationStatus.COMPLETED
                : newClaims.isEmpty() ? CompensationStatus.PENDING : status;

        Instant newCompletedAt = newStatus == CompensationStatus.COMPLETED ? Instant.now() : completedAt;

        return new CompensationState(
                newNodesToCompensate,
                newClaims,
                newCompensatedNodes,
                startedAt,
                newCompletedAt,
                newStatus);
    }

    /**
     * Mark compensation as failed
     */
    public CompensationState markFailed() {
        if (status == CompensationStatus.FAILED) {
            return this;
        }
        if (status == CompensationStatus.COMPLETED) {
            throw invalidTerminalTransition("fail completed compensation");
        }
        return new CompensationState(
                nodesToCompensate,
                List.of(),
                compensatedNodes,
                startedAt,
                Instant.now(),
                CompensationStatus.FAILED);
    }

    /**
     * Mark all remaining compensation work as completed by an external coordinator.
     */
    public CompensationState markCompleted() {
        if (status == CompensationStatus.COMPLETED) {
            return this;
        }
        if (status == CompensationStatus.FAILED) {
            throw invalidTerminalTransition("complete failed compensation");
        }

        List<NodeId> newCompensatedNodes = new ArrayList<>(compensatedNodes);
        for (NodeId nodeId : nodesToCompensate) {
            if (!newCompensatedNodes.contains(nodeId)) {
                newCompensatedNodes.add(nodeId);
            }
        }

        return new CompensationState(
                List.of(),
                List.of(),
                newCompensatedNodes,
                startedAt,
                Instant.now(),
                CompensationStatus.COMPLETED);
    }

    private void rejectTerminalTransition(String action) {
        if (status == CompensationStatus.COMPLETED || status == CompensationStatus.FAILED) {
            throw invalidTerminalTransition(action + " after compensation " + status.name().toLowerCase());
        }
    }

    private GamelanException invalidTerminalTransition(String action) {
        return new GamelanException(
                ErrorCode.RUN_COMPENSATION_NOT_READY,
                "Cannot " + action);
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
