package tech.kayys.gamelan.runtime.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final Path rootDirectory;
    private final ObjectMapper fallbackObjectMapper;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public FileWorkflowDefinitionRepository(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String rootDirectory) {
        this(FilePersistenceSupport.root(rootDirectory), FilePersistenceSupport.objectMapper());
    }

    public FileWorkflowDefinitionRepository(Path rootDirectory) {
        this(rootDirectory.toAbsolutePath().normalize(), FilePersistenceSupport.objectMapper());
    }

    FileWorkflowDefinitionRepository(Path rootDirectory, ObjectMapper objectMapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.fallbackObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Uni<WorkflowDefinition> findById(WorkflowDefinitionId id, TenantId tenantId) {
        return Uni.createFrom().item(() -> {
            StoredDefinition stored = read(id, tenantId);
            return stored != null && stored.active() ? stored.definition() : null;
        });
    }

    @Override
    public Uni<WorkflowDefinition> findByIdIncludingInactive(WorkflowDefinitionId id, TenantId tenantId) {
        return Uni.createFrom().item(() -> {
            StoredDefinition stored = read(id, tenantId);
            return stored != null ? stored.definition() : null;
        });
    }

    @Override
    public Uni<WorkflowDefinition> save(WorkflowDefinition definition, TenantId tenantId) {
        return Uni.createFrom().item(() -> {
            StoredDefinition stored = new StoredDefinition(definition, true, Instant.now());
            Path path = definitionPath(definition.id(), tenantId);
            FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                FilePersistenceSupport.writeAtomic(rootDirectory, path, stored, mapper());
                return null;
            });
            return definition;
        });
    }

    @Override
    public Uni<List<WorkflowDefinition>> findByTenant(TenantId tenantId, boolean activeOnly) {
        return Uni.createFrom().item(() -> list(tenantId).stream()
                .filter(stored -> !activeOnly || stored.active())
                .map(StoredDefinition::definition)
                .toList());
    }

    @Override
    public Uni<WorkflowDefinition> findByName(String name, TenantId tenantId) {
        return Uni.createFrom().item(() -> list(tenantId).stream()
                .filter(StoredDefinition::active)
                .filter(stored -> stored.definition().name().equals(name))
                .max(Comparator.comparing(StoredDefinition::updatedAt))
                .map(StoredDefinition::definition)
                .orElse(null));
    }

    @Override
    public Uni<Void> setActive(WorkflowDefinitionId id, TenantId tenantId, boolean active) {
        return Uni.createFrom().voidItem().invoke(() -> {
            Path path = definitionPath(id, tenantId);
            FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                StoredDefinition stored = read(id, tenantId);
                if (stored != null) {
                    StoredDefinition updated = new StoredDefinition(stored.definition(), active, Instant.now());
                    FilePersistenceSupport.writeAtomic(rootDirectory, path, updated, mapper());
                }
                return null;
            });
        });
    }

    @Override
    public Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId) {
        return setActive(id, tenantId, false);
    }

    private List<StoredDefinition> list(TenantId tenantId) {
        return FilePersistenceSupport.listJsonFiles(definitionsDirectory(tenantId)).stream()
                .map(path -> FilePersistenceSupport.read(path, StoredDefinition.class, mapper()))
                .filter(Objects::nonNull)
                .toList();
    }

    private StoredDefinition read(WorkflowDefinitionId id, TenantId tenantId) {
        return FilePersistenceSupport.read(definitionPath(id, tenantId), StoredDefinition.class, mapper());
    }

    private Path definitionPath(WorkflowDefinitionId id, TenantId tenantId) {
        return definitionsDirectory(tenantId).resolve(FilePersistenceSupport.fileName(id.value()));
    }

    private Path definitionsDirectory(TenantId tenantId) {
        return rootDirectory
                .resolve("tenants")
                .resolve(FilePersistenceSupport.directoryName(tenantId.value()))
                .resolve("definitions")
                .normalize();
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : fallbackObjectMapper;
    }

    private record StoredDefinition(
            WorkflowDefinition definition,
            boolean active,
            Instant updatedAt) {
    }
}
