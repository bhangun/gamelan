package tech.kayys.silat.workflow;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.repository.WorkflowDefinitionRepository;

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
                        throw new NoSuchElementException(
                                "Workflow definition not found: " + id.value());
                    }

                    // Cache it
                    cache.put(cacheKey, definition);
                    LOG.debug("Loaded and cached definition: {}", id.value());

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

        // Validate definition
        if (!definition.isValid()) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Invalid workflow definition"));
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
     * List all definitions for a tenant
     */
    public Uni<List<WorkflowDefinition>> listDefinitions(
            TenantId tenantId,
            boolean activeOnly) {

        return repository.findByTenant(tenantId, activeOnly);
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
     * Clear entire cache
     */
    public void clearCache() {
        cache.clear();
        LOG.info("Cleared definition cache");
    }
}
