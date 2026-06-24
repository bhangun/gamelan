package tech.kayys.gamelan.runtime.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLeaseRepository;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileWorkflowRecoveryLeaseRepository implements WorkflowRecoveryLeaseRepository {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

    private final Path rootDirectory;
    private final ObjectMapper fallbackObjectMapper;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public FileWorkflowRecoveryLeaseRepository(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String rootDirectory) {
        this(FilePersistenceSupport.root(rootDirectory), FilePersistenceSupport.objectMapper());
    }

    public FileWorkflowRecoveryLeaseRepository(Path rootDirectory) {
        this(rootDirectory.toAbsolutePath().normalize(), FilePersistenceSupport.objectMapper());
    }

    FileWorkflowRecoveryLeaseRepository(Path rootDirectory, ObjectMapper objectMapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.fallbackObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Uni<WorkflowRecoveryLease> tryAcquireRecoveryLease(
            String leaseName,
            String ownerId,
            Duration ttl,
            Instant now) {
        return Uni.createFrom().item(() -> {
            String safeLeaseName = requireText(leaseName, "leaseName");
            String safeOwnerId = requireText(ownerId, "ownerId");
            Instant acquiredAt = now != null ? now : Instant.now();
            Duration safeTtl = positiveDuration(ttl, DEFAULT_TTL);
            Instant expiresAt = acquiredAt.plus(safeTtl);
            Path path = leasePath(safeLeaseName);

            return FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                StoredRecoveryLease current = readLease(path);
                if (current != null && !current.expiredAt(acquiredAt) && !safeOwnerId.equals(current.ownerId())) {
                    return WorkflowRecoveryLease.notAcquired(safeLeaseName, safeOwnerId);
                }

                StoredRecoveryLease next = new StoredRecoveryLease(
                        safeLeaseName,
                        safeOwnerId,
                        acquiredAt,
                        expiresAt,
                        acquiredAt);
                FilePersistenceSupport.writeAtomic(rootDirectory, path, next, mapper());
                return WorkflowRecoveryLease.acquired(safeLeaseName, safeOwnerId, acquiredAt, expiresAt);
            });
        });
    }

    @Override
    public Uni<Void> releaseRecoveryLease(WorkflowRecoveryLease lease) {
        return Uni.createFrom().voidItem().invoke(() -> {
            if (lease == null || !lease.acquired()) {
                return;
            }

            Path path = leasePath(lease.leaseName());
            FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                StoredRecoveryLease current = readLease(path);
                if (current != null && lease.ownerId().equals(current.ownerId())) {
                    FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                }
                return null;
            });
        });
    }

    private StoredRecoveryLease readLease(Path path) {
        try {
            return FilePersistenceSupport.read(path, StoredRecoveryLease.class, mapper());
        } catch (UncheckedIOException error) {
            quarantineUnreadableLease(path);
            return null;
        }
    }

    private void quarantineUnreadableLease(Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            Path quarantineDirectory = path.getParent().resolve("corrupt");
            Files.createDirectories(quarantineDirectory);
            Path quarantinePath = quarantineDirectory.resolve(path.getFileName()
                    + "." + Instant.now().toEpochMilli()
                    + "." + System.nanoTime()
                    + ".corrupt");
            Files.move(path, quarantinePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Path leasePath(String leaseName) {
        return rootDirectory.resolve("recovery-leases").resolve(FilePersistenceSupport.fileName(leaseName));
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : fallbackObjectMapper;
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private record StoredRecoveryLease(
            String leaseName,
            String ownerId,
            Instant acquiredAt,
            Instant expiresAt,
            Instant updatedAt) {

        private boolean expiredAt(Instant reference) {
            return expiresAt == null || !expiresAt.isAfter(reference);
        }
    }
}
