package tech.kayys.silat.model;

import java.time.Instant;

/**
 * Suspension Info - Tracks why workflow is suspended
 */
public record SuspensionInfo(
        String reason,
        NodeId waitingOnNodeId,
        Instant suspendedAt) {
}
