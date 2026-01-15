package tech.kayys.silat.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.model.CommunicationType;
import tech.kayys.silat.model.ExecutorHealthInfo;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.registry.metrics.RegistryMetricsService;
import tech.kayys.silat.registry.persistence.ExecutorRepository;
import tech.kayys.silat.plugin.impl.PluginManager;
import tech.kayys.silat.plugin.discovery.ServiceDiscoveryPlugin;

/**
 * Executor Registry - Manages executor discovery and health monitoring
 */
@ApplicationScoped
public class ExecutorRegistry implements ExecutorRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorRegistry.class);

    // Time threshold for considering an executor unhealthy (30 seconds)
    private static final Duration HEALTH_THRESHOLD = Duration.ofSeconds(30);

    // In-memory registry (could be backed by Consul, K8s, etc.)
    private final Map<String, ExecutorInfo> executors = new ConcurrentHashMap<>();
    private final Map<String, ExecutorHealthInfo> healthInfo = new ConcurrentHashMap<>();
    private final Map<NodeId, List<String>> nodeExecutorCache = new ConcurrentHashMap<>(); // Cache for node-executor
                                                                                           // mapping

    // Selection strategies
    private final RoundRobinSelectionStrategy roundRobinStrategy = new RoundRobinSelectionStrategy();
    private final RandomSelectionStrategy randomStrategy = new RandomSelectionStrategy();
    private final WeightedSelectionStrategy weightedStrategy = new WeightedSelectionStrategy();

    // Default strategy
    private ExecutorSelectionStrategy defaultStrategy = roundRobinStrategy;

    @Inject
    ExecutorRepository executorRepository;

    @Inject
    RegistryMetricsService metricsService;

    @Inject
    PluginManager pluginManager;

    // Initialize metrics service after injection
    @jakarta.annotation.PostConstruct
    void initializeMetrics() {
        // Initialize metrics service with a supplier that returns the current executor count
        metricsService.initialize(() -> executors.size());
    }

    @Override
    public Uni<Optional<ExecutorInfo>> getExecutorForNode(NodeId nodeId) {
        return Uni.createFrom().deferred(() -> {
            var timerSample = metricsService.startSelectionTimer();
            Optional<ExecutorInfo> result = selectBestExecutorForNode(nodeId);
            metricsService.stopSelectionTimer(timerSample);
            if (result.isPresent()) {
                metricsService.incrementSelection();
            }
            return Uni.createFrom().item(result);
        });
    }

    @Override
    public Uni<List<ExecutorInfo>> getAllExecutors() {
        return Uni.createFrom().item(new ArrayList<>(executors.values()));
    }

    @Override
    public Uni<List<ExecutorInfo>> getHealthyExecutors() {
        Instant threshold = Instant.now().minus(HEALTH_THRESHOLD);

        List<ExecutorInfo> healthyExecutors = executors.values().stream()
                .filter(executor -> {
                    ExecutorHealthInfo health = healthInfo.get(executor.executorId());
                    return health != null && health.lastHeartbeat.isAfter(threshold);
                })
                .collect(Collectors.toList());

        return Uni.createFrom().item(healthyExecutors);
    }

    @Override
    public Uni<Void> registerExecutor(ExecutorInfo executor) {
        executors.put(executor.executorId(), executor);

        // Initialize health info
        healthInfo.put(executor.executorId(), new ExecutorHealthInfo(executor.executorId()));

        // Persist to storage
        return executorRepository.save(executor)
                .invoke(() -> {
                    LOG.info("Registered executor: {} (type: {}, communication: {})",
                            executor.executorId(), executor.executorType(), executor.communicationType());
                    metricsService.incrementRegistration();
                });
    }

    @Override
    public Uni<Void> unregisterExecutor(String executorId) {
        executors.remove(executorId);
        healthInfo.remove(executorId);

        // Remove from persistent storage
        return executorRepository.delete(executorId)
                .invoke(() -> {
                    LOG.info("Unregistered executor: {}", executorId);
                    metricsService.incrementUnregistration();
                });
    }

    @Override
    public Uni<Void> heartbeat(String executorId) {
        ExecutorHealthInfo health = healthInfo.get(executorId);
        if (health != null) {
            health.updateHeartbeat();
            LOG.debug("Heartbeat updated for executor: {}", executorId);
            metricsService.incrementHeartbeat();
        } else {
            LOG.warn("Heartbeat from unregistered executor: {}", executorId);
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Optional<ExecutorHealthInfo>> getHealthInfo(String executorId) {
        return Uni.createFrom().item(Optional.ofNullable(healthInfo.get(executorId)));
    }

    @Override
    public Uni<Boolean> isHealthy(String executorId) {
        ExecutorHealthInfo health = healthInfo.get(executorId);
        if (health == null) {
            return Uni.createFrom().item(false);
        }

        Instant threshold = Instant.now().minus(HEALTH_THRESHOLD);
        boolean isHealthy = health.lastHeartbeat.isAfter(threshold);
        return Uni.createFrom().item(isHealthy);
    }

    @Override
    public Uni<Optional<ExecutorInfo>> getExecutorById(String executorId) {
        return Uni.createFrom().item(() -> {
            ExecutorInfo cached = executors.get(executorId);
            if (cached != null) {
                return applyServiceDiscovery(cached);
            }
            return null;
        })
                .flatMap(cached -> {
                    if (cached != null) {
                        return Uni.createFrom().item(Optional.of(cached));
                    }
                    // If not in cache, try to load from persistent storage
                    return executorRepository.findById(executorId)
                            .invoke(executorOpt -> executorOpt
                                    .ifPresent(executor -> executors.put(executorId, executor)))
                            .map(opt -> opt.map(this::applyServiceDiscovery));
                });
    }

    private ExecutorInfo applyServiceDiscovery(ExecutorInfo executor) {
        if (pluginManager == null)
            return executor;

        Optional<ServiceDiscoveryPlugin> discoveryPlugin = pluginManager.getAllPlugins().stream()
                .filter(p -> p instanceof ServiceDiscoveryPlugin)
                .map(p -> (ServiceDiscoveryPlugin) p)
                .findFirst();

        if (discoveryPlugin.isPresent()) {
            Optional<String> discoveredEndpoint = discoveryPlugin.get().discoverEndpoint(executor.executorId());
            if (discoveredEndpoint.isPresent()) {
                LOG.debug("Service Discovery: Overriding endpoint for {} from {} to {}",
                        executor.executorId(), executor.endpoint(), discoveredEndpoint.get());

                return new ExecutorInfo(
                        executor.executorId(),
                        executor.executorType(),
                        executor.communicationType(),
                        discoveredEndpoint.get(),
                        executor.timeout(),
                        executor.metadata());
            }
        }
        return executor;
    }

    @Override
    public Uni<List<ExecutorInfo>> getExecutorsByType(String executorType) {
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executor.executorType().equals(executorType))
                .collect(Collectors.toList());
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(CommunicationType communicationType) {
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executor.communicationType() == communicationType)
                .collect(Collectors.toList());
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<Void> updateExecutorMetadata(String executorId, Map<String, String> metadata) {
        ExecutorInfo executor = executors.get(executorId);
        if (executor != null) {
            // Create a new executor with updated metadata
            ExecutorInfo updatedExecutor = new ExecutorInfo(
                    executor.executorId(),
                    executor.executorType(),
                    executor.communicationType(),
                    executor.endpoint(),
                    executor.timeout(),
                    metadata);
            executors.put(executorId, updatedExecutor);

            // Update in persistent storage
            return executorRepository.save(updatedExecutor)
                    .invoke(() -> LOG.debug("Updated metadata for executor: {}", executorId));
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Integer> getExecutorCount() {
        return Uni.createFrom().item(executors.size());
    }

    @Override
    public Uni<ExecutorStatistics> getStatistics() {
        Instant threshold = Instant.now().minus(HEALTH_THRESHOLD);

        int totalExecutors = executors.size();
        int healthyCount = 0;
        Map<String, Integer> executorsByType = new HashMap<>();
        Map<CommunicationType, Integer> executorsByCommType = new HashMap<>();

        for (Map.Entry<String, ExecutorInfo> entry : executors.entrySet()) {
            ExecutorInfo executor = entry.getValue();
            ExecutorHealthInfo health = healthInfo.get(executor.executorId());

            if (health != null && health.lastHeartbeat.isAfter(threshold)) {
                healthyCount++;
            }

            // Count by type
            executorsByType.merge(executor.executorType(), 1, Integer::sum);

            // Count by communication type
            executorsByCommType.merge(executor.communicationType(), 1, Integer::sum);
        }

        int unhealthyCount = totalExecutors - healthyCount;

        ExecutorStatistics stats = new ExecutorStatistics(
                totalExecutors,
                healthyCount,
                unhealthyCount,
                executorsByType,
                executorsByCommType,
                System.currentTimeMillis());

        return Uni.createFrom().item(stats);
    }

    /**
     * Select the best executor for a given node using the configured strategy
     */
    private Optional<ExecutorInfo> selectBestExecutorForNode(NodeId nodeId) {
        List<ExecutorInfo> availableExecutors = executors.values().stream()
                .filter(this::isHealthyNow)
                .collect(Collectors.toList());

        if (availableExecutors.isEmpty()) {
            LOG.warn("No healthy executors available for node: {}", nodeId.value());
            return Optional.empty();
        }

        // Use the configured selection strategy
        Optional<ExecutorInfo> selected = defaultStrategy.select(nodeId, availableExecutors, Map.of());

        if (selected.isPresent()) {
            LOG.debug("Selected executor {} for node {} using {} strategy",
                    selected.get().executorId(), nodeId.value(), defaultStrategy.getName());
        } else {
            LOG.warn("No executor could be selected for node: {}", nodeId.value());
        }

        return selected;
    }

    /**
     * Check if an executor is currently healthy
     */
    private boolean isHealthyNow(ExecutorInfo executor) {
        ExecutorHealthInfo health = healthInfo.get(executor.executorId());
        if (health == null) {
            return false;
        }

        Instant threshold = Instant.now().minus(HEALTH_THRESHOLD);
        return health.lastHeartbeat.isAfter(threshold);
    }

    /**
     * Set the selection strategy to use
     */
    public void setSelectionStrategy(ExecutorSelectionStrategy strategy) {
        this.defaultStrategy = strategy;
        LOG.info("Set executor selection strategy to: {}", strategy.getName());
    }

    /**
     * Get the current selection strategy
     */
    public ExecutorSelectionStrategy getSelectionStrategy() {
        return this.defaultStrategy;
    }

    /**
     * Load all executors from persistent storage into memory
     */
    public Uni<Void> loadFromPersistentStorage() {
        return executorRepository.findAll()
                .invoke(persistentExecutors -> {
                    for (ExecutorInfo executor : persistentExecutors) {
                        executors.put(executor.executorId(), executor);
                        // Initialize health info for loaded executors
                        if (!healthInfo.containsKey(executor.executorId())) {
                            healthInfo.put(executor.executorId(), new ExecutorHealthInfo(executor.executorId()));
                        }
                    }
                    LOG.info("Loaded {} executors from persistent storage", persistentExecutors.size());
                })
                .replaceWithVoid();
    }
}
