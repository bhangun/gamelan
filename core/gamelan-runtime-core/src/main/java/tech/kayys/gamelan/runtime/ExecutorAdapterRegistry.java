package tech.kayys.gamelan.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing executor adapters.
 * Supports registration and lookup of adapters by executor type.
 */
@ApplicationScoped
public class ExecutorAdapterRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorAdapterRegistry.class);

    private final Map<String, ExecutorAdapter> adapters;

    @Inject
    public ExecutorAdapterRegistry(Instance<ExecutorAdapter> adapterInstances) {
        this.adapters = new ConcurrentHashMap<>();

        // Auto-register all CDI-discovered adapters
        adapterInstances.stream().forEach(adapter -> {
            register(adapter);
            LOG.info("Registered executor adapter: {}", adapter.getExecutorType());
        });
    }

    /**
     * Register an executor adapter
     */
    public void register(ExecutorAdapter adapter) {
        String type = adapter.getExecutorType();
        if (adapters.containsKey(type)) {
            LOG.warn("Overwriting existing adapter for type: {}", type);
        }
        adapters.put(type, adapter);
    }

    /**
     * Get adapter for the given executor type
     */
    public ExecutorAdapter getAdapter(String executorType) {
        ExecutorAdapter adapter = adapters.get(executorType);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter found for executor type: " + executorType);
        }
        return adapter;
    }

    /**
     * Check if an adapter exists for the given type
     */
    public boolean hasAdapter(String executorType) {
        return adapters.containsKey(executorType);
    }

    /**
     * Get all registered executor types
     */
    public Map<String, ExecutorAdapter> getAllAdapters() {
        return Map.copyOf(adapters);
    }
}
