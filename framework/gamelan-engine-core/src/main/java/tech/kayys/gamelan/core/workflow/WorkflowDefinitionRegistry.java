package tech.kayys.gamelan.core.workflow;

import java.util.List;
import java.util.Map;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;

/**
 * Registry for workflow definitions with caching
 */
@ApplicationScoped
public class WorkflowDefinitionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowDefinitionRegistry.class);

    @Inject
    WorkflowDefinitionRepository repository;

    // In-memory cache
    private final Map<String, WorkflowDefinition> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Get workflow definition by ID
     */
    public Uni<WorkflowDefinition> getDefinition(
            WorkflowDefinitionId id,
            TenantId tenantId) {

        String cacheKey = tenantId.value() + ":" + id.value();

        // Check cache first
        WorkflowDefinition cached = cache.get(cacheKey);
        if (cached != null) {
            LOG.trace("Definition found in cache: {}", id.value());
            return Uni.createFrom().item(cached);
        }

        // Load from repository
        return repository.findById(id, tenantId)
                .map(definition -> {
                    if (definition == null) {
                        throw new GamelanException(
                                ErrorCode.WORKFLOW_NOT_FOUND,
                                "Workflow definition not found: " + id.value());
                    }

                    // Cache it
                    cache.put(cacheKey, definition);
                    LOG.debug("Loaded and cached definition: {}", id.value());

                    return definition;
                });
    }

    /**
     * Load a definition regardless of active state. This is intentionally not
     * cached because public execution paths should only resolve active definitions.
     */
    public Uni<WorkflowDefinition> getDefinitionIncludingInactive(
            WorkflowDefinitionId id,
            TenantId tenantId) {

        return repository.findByIdIncludingInactive(id, tenantId)
                .map(definition -> {
                    if (definition == null) {
                        throw new GamelanException(
                                ErrorCode.WORKFLOW_NOT_FOUND,
                                "Workflow definition not found: " + id.value());
                    }
                    return definition;
                });
    }

    /**
     * Register a new workflow definition
     */
    public Uni<WorkflowDefinition> register(
            WorkflowDefinition definition,
            TenantId tenantId) {

        LOG.info("Registering workflow definition: {} v{}",
                definition.name(), definition.version());

        ValidationResult validation = definition.validate();
        if (!validation.isValid()) {
            return Uni.createFrom().failure(
                    new GamelanException(
                            ErrorCode.WORKFLOW_INVALID_DEFINITION,
                            validation.message() + ": " + String.join("; ", validation.errors())));
        }

        // Save to repository
        return repository.save(definition, tenantId)
                .map(saved -> {
                    // Update cache
                    String cacheKey = tenantId.value() + ":" + saved.id().value();
                    cache.put(cacheKey, saved);

                    LOG.info("Registered workflow definition: {}", saved.id().value());
                    return saved;
                });
    }

    /**
     * Update an existing workflow definition and synchronize its active state.
     */
    public Uni<WorkflowDefinition> update(
            WorkflowDefinition definition,
            TenantId tenantId,
            boolean active) {

        LOG.info("Updating workflow definition: {} v{} active={}",
                definition.name(), definition.version(), active);

        ValidationResult validation = definition.validate();
        if (!validation.isValid()) {
            return Uni.createFrom().failure(
                    new GamelanException(
                            ErrorCode.WORKFLOW_INVALID_DEFINITION,
                            validation.message() + ": " + String.join("; ", validation.errors())));
        }

        return repository.save(definition, tenantId)
                .call(saved -> repository.setActive(saved.id(), tenantId, active))
                .map(saved -> {
                    String cacheKey = tenantId.value() + ":" + saved.id().value();
                    if (active) {
                        cache.put(cacheKey, saved);
                    } else {
                        cache.remove(cacheKey);
                    }
                    return saved;
                });
    }

    /**
     * List all definitions for a tenant
     */
    public Uni<List<WorkflowDefinition>> listDefinitions(
            TenantId tenantId,
            boolean activeOnly) {

        return repository.findByTenant(tenantId, activeOnly);
    }

    /**
     * Get workflow definition by name
     */
    public Uni<WorkflowDefinition> getByName(String name, TenantId tenantId) {
        // Cache could potentially be used here too, but for now repository call is
        // safer for 'latest version' semantics if not specified
        return repository.findByName(name, tenantId);
    }

    /**
     * Invalidate cache for a definition
     */
    public void invalidateCache(WorkflowDefinitionId id, TenantId tenantId) {
        String cacheKey = tenantId.value() + ":" + id.value();
        cache.remove(cacheKey);
        LOG.debug("Invalidated cache for: {}", id.value());
    }

    /**
     * Deactivate a definition and remove it from active-definition cache.
     */
    public Uni<Void> deleteDefinition(WorkflowDefinitionId id, TenantId tenantId) {
        return repository.delete(id, tenantId)
                .invoke(() -> invalidateCache(id, tenantId));
    }

    /**
     * Clear entire cache
     */
    public void clearCache() {
        cache.clear();
        LOG.info("Cleared definition cache");
    }
}
