package tech.kayys.gamelan.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Timer;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorHealthInfo;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.registry.metrics.RegistryMetricsService;
import tech.kayys.gamelan.registry.persistence.ExecutorRepository;
import tech.kayys.gamelan.engine.plugin.PluginManager;
import tech.kayys.gamelan.plugin.discovery.ServiceDiscoveryPlugin;

/**
 * Executor Registry - Manages executor discovery and health monitoring
 */
@ApplicationScoped
public class ExecutorRegistry implements ExecutorRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorRegistry.class);

    // Time threshold for considering an executor unhealthy
    @ConfigProperty(name = "gamelan.registry.health.threshold", defaultValue = "30s")
    Duration healthThreshold;

    // Time threshold for removing an executor from registry
    @ConfigProperty(name = "gamelan.registry.stale.threshold", defaultValue = "5m")
    Duration staleThreshold;

    // Interval for running the cleanup task
    @ConfigProperty(name = "gamelan.registry.cleanup.interval", defaultValue = "1m")
    Duration cleanupInterval;

    // Default selection strategy
    @ConfigProperty(name = "gamelan.registry.selection.strategy", defaultValue = "round-robin")
    String defaultStrategyName;

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

    private final java.util.concurrent.ScheduledExecutorService cleanupExecutor = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "gamelan-registry-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    @Inject
    ExecutorRepository executorRepository;

    @Inject
    RegistryMetricsService metricsService;

    @Inject
    PluginManager pluginManager;

    // Initialize metrics service after injection
    @jakarta.annotation.PostConstruct
    void init() {
        // Initialize metrics service with a supplier that returns the current executor
        // count
        metricsService.initialize(() -> executors.size());

        // Set default strategy from config
        if ("random".equalsIgnoreCase(defaultStrategyName)) {
            defaultStrategy = randomStrategy;
        } else if ("weighted".equalsIgnoreCase(defaultStrategyName)) {
            defaultStrategy = weightedStrategy;
        } else {
            defaultStrategy = roundRobinStrategy;
        }

        LOG.info("ExecutorRegistry initialized with healthThreshold={}, staleThreshold={}, strategy={}",
                healthThreshold, staleThreshold, defaultStrategy.getName());

        // Start cleanup task
        cleanupExecutor.scheduleAtFixedRate(this::cleanupStaleExecutors,
                cleanupInterval.toMillis(), cleanupInterval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupExecutor.shutdownNow();
        }
    }

    private void cleanupStaleExecutors() {
        Instant now = Instant.now();
        Instant staleInstant = now.minus(staleThreshold);
        List<String> toRemove = new ArrayList<>();

        healthInfo.forEach((executorId, health) -> {
            if (health.lastHeartbeat.isBefore(staleInstant)) {
                toRemove.add(executorId);
            }
        });

        if (!toRemove.isEmpty()) {
            LOG.info("Cleaning up {} stale executors: {}", toRemove.size(), toRemove);
            toRemove.forEach(id -> {
                unregisterExecutor(id).subscribe().with(
                        item -> LOG.debug("Successfully cleaned up stale executor: {}", id),
                        failure -> LOG.error("Failed to clean up stale executor: {}", id, failure));
            });
        }
    }

    @Override
    public Uni<Optional<ExecutorInfo>> getExecutorForNode(NodeId nodeId) {
        return Uni.createFrom().deferred(() -> {
            Timer.Sample timerSample = metricsService.startSelectionTimer();

            // Check cache first
            List<String> cachedExecutorIds = nodeExecutorCache.get(nodeId);
            if (cachedExecutorIds != null && !cachedExecutorIds.isEmpty()) {
                // Filter healthy from cache
                List<ExecutorInfo> healthyCached = cachedExecutorIds.stream()
                        .map(executors::get)
                        .filter(e -> e != null && isHealthyNow(e))
                        .collect(Collectors.toList());

                if (!healthyCached.isEmpty()) {
                    Optional<ExecutorInfo> selected = defaultStrategy.select(nodeId, healthyCached, Map.of());
                    metricsService.stopSelectionTimer(timerSample);
                    if (selected.isPresent()) {
                        metricsService.incrementSelection();
                        return Uni.createFrom().item(selected);
                    }
                }
            }

            Optional<ExecutorInfo> result = selectBestExecutorForNode(nodeId);
            metricsService.stopSelectionTimer(timerSample);
            if (result.isPresent()) {
                metricsService.incrementSelection();
                // Cache the selection (simplified: just store the selected for now, or update
                // the list)
                // In a production system, we'd probably cache all compatible healthy executors
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
        Instant threshold = Instant.now().minus(healthThreshold);

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

        // Invalidate cache
        nodeExecutorCache.clear();

        // Persist to storage
        return executorRepository.save(executor)
                .onItem().invoke(() -> {
                    LOG.info("Registered executor: {} (type: {}, communication: {})",
                            executor.executorId(), executor.executorType(), executor.communicationType());
                    metricsService.incrementRegistration();
                    metricsService.incrementExecutorCount();
                });
    }

    @Override
    public Uni<Void> unregisterExecutor(String executorId) {
        if (executors.remove(executorId) != null) {
            healthInfo.remove(executorId);
            nodeExecutorCache.clear();
            metricsService.decrementExecutorCount();
        }

        // Remove from persistent storage
        return executorRepository.delete(executorId)
                .onItem().invoke(() -> {
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

        Instant threshold = Instant.now().minus(healthThreshold);
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

        List<ServiceDiscoveryPlugin> discoveryPlugins = pluginManager.getPluginsByType(ServiceDiscoveryPlugin.class);
        if (discoveryPlugins.isEmpty()) {
            return executor;
        }

        for (ServiceDiscoveryPlugin plugin : discoveryPlugins) {
            Optional<String> discoveredEndpoint = plugin.discoverEndpoint(executor.executorId());
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
        Instant threshold = Instant.now().minus(healthThreshold);

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

        Instant threshold = Instant.now().minus(healthThreshold);
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
                .onItem().invoke(persistentExecutors -> {
                    for (ExecutorInfo executor : persistentExecutors) {
                        if (executors.put(executor.executorId(), executor) == null) {
                            metricsService.incrementExecutorCount();
                        }
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
