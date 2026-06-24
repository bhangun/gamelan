package tech.kayys.gamelan.engine.repository;

import java.time.Duration;
import java.time.Instant;

import io.smallrye.mutiny.Uni;

public interface WorkflowRecoveryLeaseRepository {

    Uni<WorkflowRecoveryLease> tryAcquireRecoveryLease(
            String leaseName,
            String ownerId,
            Duration ttl,
            Instant now);

    Uni<Void> releaseRecoveryLease(WorkflowRecoveryLease lease);
}
