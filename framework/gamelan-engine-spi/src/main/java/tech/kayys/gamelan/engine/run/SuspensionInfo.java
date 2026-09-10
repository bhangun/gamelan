package tech.kayys.gamelan.engine.run;

import java.time.Instant;

import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Suspension Info - Tracks why workflow is suspended
 */
public record SuspensionInfo(
                String reason,
                NodeId waitingOnNodeId,
                Instant suspendedAt) {
}
