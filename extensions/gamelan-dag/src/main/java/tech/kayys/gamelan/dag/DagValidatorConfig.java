package tech.kayys.gamelan.dag;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Configuration class for DAG validator plugin.
 * Defines configurable parameters for DAG validation rules.
 */
@ApplicationScoped
public class DagValidatorConfig {

    @Inject
    @ConfigProperty(name = "gamelan.dag.validator.enabled", defaultValue = "true")
    boolean dagValidatorEnabled;

    @Inject
    @ConfigProperty(name = "gamelan.dag.validator.allowMultipleRoots", defaultValue = "false")
    boolean allowMultipleRoots;

    @Inject
    @ConfigProperty(name = "gamelan.dag.validator.allowOrphanNodes", defaultValue = "false")
    boolean allowOrphanNodes;

    @Inject
    @ConfigProperty(name = "gamelan.dag.validator.enforceTopologicalOrdering", defaultValue = "true")
    boolean enforceTopologicalOrdering;

    @Inject
    @ConfigProperty(name = "gamelan.dag.validator.maxDepth", defaultValue = "100")
    int maxDepth;

    @Inject
    @ConfigProperty(name = "gamelan.dag.validator.maxWidth", defaultValue = "50")
    int maxWidth;

    public boolean isDagValidatorEnabled() {
        return dagValidatorEnabled;
    }

    public boolean isAllowMultipleRoots() {
        return allowMultipleRoots;
    }

    public boolean isAllowOrphanNodes() {
        return allowOrphanNodes;
    }

    public boolean isEnforceTopologicalOrdering() {
        return enforceTopologicalOrdering;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getMaxWidth() {
        return maxWidth;
    }
}