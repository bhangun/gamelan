package tech.kayys.gamelan.engine.repository;

import java.time.Instant;
import java.util.Objects;

public record WorkflowRecoveryLease(
        String leaseName,
        String ownerId,
        Instant acquiredAt,
        Instant expiresAt,
        boolean acquired) {

    public WorkflowRecoveryLease {
        leaseName = requireText(leaseName, "leaseName");
        ownerId = ownerId != null && !ownerId.isBlank() ? ownerId.trim() : "";
        if (acquired) {
            acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt cannot be null for acquired leases");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt cannot be null for acquired leases");
        }
    }

    public static WorkflowRecoveryLease acquired(
            String leaseName,
            String ownerId,
            Instant acquiredAt,
            Instant expiresAt) {
        return new WorkflowRecoveryLease(leaseName, ownerId, acquiredAt, expiresAt, true);
    }

    public static WorkflowRecoveryLease notAcquired(String leaseName, String ownerId) {
        return new WorkflowRecoveryLease(leaseName, ownerId, null, null, false);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
