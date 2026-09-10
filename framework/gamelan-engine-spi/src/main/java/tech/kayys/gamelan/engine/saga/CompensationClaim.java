package tech.kayys.gamelan.engine.saga;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Durable lease for a compensation node currently being rolled back.
 */
public record CompensationClaim(
        NodeId nodeId,
        String claimId,
        Instant claimedAt,
        Instant expiresAt) {
    public CompensationClaim {
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        claimId = claimId != null && !claimId.isBlank() ? claimId : UUID.randomUUID().toString();
        claimedAt = claimedAt != null ? claimedAt : Instant.now();
        expiresAt = expiresAt != null ? expiresAt : claimedAt;
    }

    public boolean isActive(Instant now) {
        Instant referenceTime = now != null ? now : Instant.now();
        return expiresAt.isAfter(referenceTime);
    }
}
