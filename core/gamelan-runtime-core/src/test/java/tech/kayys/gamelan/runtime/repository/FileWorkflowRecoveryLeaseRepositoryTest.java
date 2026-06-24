package tech.kayys.gamelan.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;

class FileWorkflowRecoveryLeaseRepositoryTest {

    private static final String LEASE_NAME = "workflow-recovery";
    private static final Duration TTL = Duration.ofMinutes(1);
    private static final Instant NOW = Instant.parse("2026-06-02T01:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void tryAcquireRecoveryLease_acquiresAndBlocksOtherOwnerUntilExpiry() {
        FileWorkflowRecoveryLeaseRepository writer = new FileWorkflowRecoveryLeaseRepository(tempDir);
        FileWorkflowRecoveryLeaseRepository competingReader = new FileWorkflowRecoveryLeaseRepository(tempDir);

        WorkflowRecoveryLease ownerA = writer.tryAcquireRecoveryLease(LEASE_NAME, "owner-a", TTL, NOW)
                .await().indefinitely();
        WorkflowRecoveryLease blockedOwnerB = competingReader.tryAcquireRecoveryLease(
                LEASE_NAME,
                "owner-b",
                TTL,
                NOW.plusSeconds(30)).await().indefinitely();
        WorkflowRecoveryLease expiredOwnerB = competingReader.tryAcquireRecoveryLease(
                LEASE_NAME,
                "owner-b",
                TTL,
                NOW.plusSeconds(61)).await().indefinitely();

        assertTrue(ownerA.acquired());
        assertEquals(NOW.plus(TTL), ownerA.expiresAt());
        assertTrue(Files.isRegularFile(leasePath(LEASE_NAME)));
        assertFalse(blockedOwnerB.acquired());
        assertTrue(expiredOwnerB.acquired());
        assertEquals("owner-b", expiredOwnerB.ownerId());
    }

    @Test
    void tryAcquireRecoveryLease_renewsSameOwnerBeforeExpiry() {
        FileWorkflowRecoveryLeaseRepository repository = new FileWorkflowRecoveryLeaseRepository(tempDir);

        WorkflowRecoveryLease first = repository.tryAcquireRecoveryLease(LEASE_NAME, "owner-a", TTL, NOW)
                .await().indefinitely();
        WorkflowRecoveryLease renewed = repository.tryAcquireRecoveryLease(
                LEASE_NAME,
                "owner-a",
                TTL,
                NOW.plusSeconds(30)).await().indefinitely();
        WorkflowRecoveryLease blockedOwnerB = repository.tryAcquireRecoveryLease(
                LEASE_NAME,
                "owner-b",
                TTL,
                NOW.plusSeconds(61)).await().indefinitely();
        WorkflowRecoveryLease expiredOwnerB = repository.tryAcquireRecoveryLease(
                LEASE_NAME,
                "owner-b",
                TTL,
                NOW.plusSeconds(91)).await().indefinitely();

        assertTrue(first.acquired());
        assertTrue(renewed.acquired());
        assertEquals(NOW.plusSeconds(30), renewed.acquiredAt());
        assertEquals(NOW.plusSeconds(90), renewed.expiresAt());
        assertFalse(blockedOwnerB.acquired());
        assertTrue(expiredOwnerB.acquired());
    }

    @Test
    void releaseRecoveryLease_releasesOnlyMatchingOwner() {
        FileWorkflowRecoveryLeaseRepository repository = new FileWorkflowRecoveryLeaseRepository(tempDir);
        WorkflowRecoveryLease ownerA = repository.tryAcquireRecoveryLease(LEASE_NAME, "owner-a", TTL, NOW)
                .await().indefinitely();
        WorkflowRecoveryLease wrongOwner = WorkflowRecoveryLease.acquired(
                LEASE_NAME,
                "owner-b",
                NOW.plusSeconds(10),
                NOW.plusSeconds(70));

        repository.releaseRecoveryLease(wrongOwner).await().indefinitely();
        WorkflowRecoveryLease stillBlocked = repository.tryAcquireRecoveryLease(
                LEASE_NAME,
                "owner-c",
                TTL,
                NOW.plusSeconds(30)).await().indefinitely();

        repository.releaseRecoveryLease(ownerA).await().indefinitely();
        WorkflowRecoveryLease releasedOwnerC = repository.tryAcquireRecoveryLease(
                LEASE_NAME,
                "owner-c",
                TTL,
                NOW.plusSeconds(31)).await().indefinitely();

        assertFalse(stillBlocked.acquired());
        assertTrue(releasedOwnerC.acquired());
    }

    @Test
    void tryAcquireRecoveryLease_quarantinesUnreadableLeaseFileAndAcquiresFreshLease() throws Exception {
        FileWorkflowRecoveryLeaseRepository repository = new FileWorkflowRecoveryLeaseRepository(tempDir);
        Path path = leasePath(LEASE_NAME);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{not-json");

        WorkflowRecoveryLease lease = repository.tryAcquireRecoveryLease(LEASE_NAME, "owner-a", TTL, NOW)
                .await().indefinitely();

        assertTrue(lease.acquired());
        assertEquals("owner-a", lease.ownerId());
        assertTrue(Files.isRegularFile(path));
        Path quarantineDirectory = tempDir.resolve("recovery-leases").resolve("corrupt");
        try (var paths = Files.list(quarantineDirectory)) {
            List<Path> quarantined = paths.toList();
            assertEquals(1, quarantined.size());
            assertTrue(Files.readString(quarantined.getFirst()).contains("not-json"));
        }
    }

    @Test
    void tryAcquireRecoveryLease_rejectsBlankLeaseNamesAndOwners() {
        FileWorkflowRecoveryLeaseRepository repository = new FileWorkflowRecoveryLeaseRepository(tempDir);

        assertThrows(
                IllegalArgumentException.class,
                () -> repository.tryAcquireRecoveryLease(" ", "owner-a", TTL, NOW).await().indefinitely());
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.tryAcquireRecoveryLease(LEASE_NAME, " ", TTL, NOW).await().indefinitely());
    }

    private Path leasePath(String leaseName) {
        return tempDir.resolve("recovery-leases").resolve(FilePersistenceSupport.fileName(leaseName));
    }
}
