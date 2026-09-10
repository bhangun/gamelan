package tech.kayys.gamelan.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorCapabilityRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorHealthInfo;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorResourceRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons;
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

    // Prefer local executors when compatible with placement, useful for standalone/local-agent profiles.
    @ConfigProperty(name = "gamelan.registry.selection.prefer-local", defaultValue = "false")
    boolean preferLocalSelection;

    // In-memory registry (could be backed by Consul, K8s, etc.)
    private final Map<String, ExecutorInfo> executors = new ConcurrentHashMap<>();
    private final Map<String, ExecutorHealthInfo> healthInfo = new ConcurrentHashMap<>();
    private final Map<NodeId, List<String>> nodeExecutorCache = new ConcurrentHashMap<>(); // Cache for node-executor
                                                                                           // mapping

    // Selection strategies
    private final RoundRobinSelectionStrategy roundRobinStrategy = new RoundRobinSelectionStrategy();
    private final RandomSelectionStrategy randomStrategy = new RandomSelectionStrategy();
    private final WeightedSelectionStrategy weightedStrategy = new WeightedSelectionStrategy();
    private final LeastLoadedSelectionStrategy leastLoadedStrategy = new LeastLoadedSelectionStrategy();
    private final Map<String, ExecutorSelectionStrategy> selectionStrategies = new ConcurrentHashMap<>();

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

    public ExecutorRegistry() {
        registerSelectionStrategy(roundRobinStrategy);
        registerSelectionStrategy(randomStrategy);
        registerSelectionStrategy(weightedStrategy);
        registerSelectionStrategy(leastLoadedStrategy);
    }

    // Initialize metrics service after injection
    @jakarta.annotation.PostConstruct
    void init() {
        // Initialize metrics service with a supplier that returns the current executor
        // count
        metricsService.initialize(() -> executors.size());

        defaultStrategy = selectionStrategyByName(defaultStrategyName)
                .orElse(roundRobinStrategy);

        LOG.info("ExecutorRegistry initialized with healthThreshold={}, staleThreshold={}, strategy={}, preferLocal={}",
                healthThreshold, staleThreshold, defaultStrategy.getName(), preferLocalSelection);

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
        return getExecutorForNode(nodeId, ExecutorPlacementRequirements.none());
    }

    @Override
    public Uni<Optional<ExecutorInfo>> getExecutorForNode(
            NodeId nodeId,
            ExecutorPlacementRequirements placement) {
        return selectExecutor(ExecutorSelectionRequest.forNode(nodeId, placement));
    }

    @Override
    public Uni<Optional<ExecutorInfo>> selectExecutor(ExecutorSelectionRequest request) {
        return selectExecutorWithDiagnostics(request)
                .map(ExecutorSelectionReport::selectedExecutor);
    }

    @Override
    public Uni<ExecutorSelectionReport> selectExecutorWithDiagnostics(ExecutorSelectionRequest request) {
        return Uni.createFrom().deferred(() -> {
            Timer.Sample timerSample = metricsService.startSelectionTimer();
            ExecutorSelectionRequest effectiveRequest = java.util.Objects.requireNonNull(
                    request,
                    "request cannot be null");
            SelectionDiagnostics diagnostics = evaluateSelectionCandidates(effectiveRequest);
            ExecutorSelectionStrategy strategy = selectionStrategyFor(effectiveRequest);

            List<ExecutorInfo> candidatePool = applyPreferredCapabilityBias(
                    effectiveRequest.capabilityRequirements(),
                    diagnostics.candidates());
            List<ExecutorInfo> cachedCandidatePool = diagnostics.cachedCandidates().stream()
                    .filter(candidatePool::contains)
                    .toList();

            Optional<ExecutorInfo> selected = Optional.empty();
            if (!cachedCandidatePool.isEmpty()) {
                selected = selectBestExecutorForNode(
                        effectiveRequest.nodeId(),
                        cachedCandidatePool,
                        effectiveRequest.placement(),
                        additionalSelectionContext(effectiveRequest),
                        strategy);
            }

            if (selected.isEmpty()) {
                selected = selectBestExecutorForNode(
                        effectiveRequest.nodeId(),
                        candidatePool,
                        effectiveRequest.placement(),
                        additionalSelectionContext(effectiveRequest),
                        strategy);
            }

            metricsService.stopSelectionTimer(timerSample);
            if (selected.isPresent()) {
                metricsService.incrementSelection();
                // Cache the selection (simplified: just store the selected for now, or update
                // the list)
                // In a production system, we'd probably cache all compatible healthy executors
            }
            return Uni.createFrom().item(new ExecutorSelectionReport(
                    effectiveRequest,
                    selected,
                    diagnostics.totalExecutors(),
                    diagnostics.cachedExecutors(),
                    diagnostics.cachedCandidates().size(),
                    diagnostics.typeCompatibleExecutors(),
                    diagnostics.healthyExecutors(),
                    diagnostics.placementCompatibleExecutors(),
                    diagnostics.candidates().size(),
                    diagnostics.rejectionCounts(),
                    selectionDiagnosticsContext(effectiveRequest, strategy, diagnostics)));
        });
    }

    @Override
    public Uni<List<ExecutorInfo>> getAllExecutors() {
        return Uni.createFrom().item(new ArrayList<>(executors.values()));
    }

    @Override
    public Uni<List<ExecutorInfo>> getHealthyExecutors() {
        List<ExecutorInfo> healthyExecutors = executors.values().stream()
                .filter(this::isHealthyNow)
                .collect(Collectors.toList());

        return Uni.createFrom().item(healthyExecutors);
    }

    @Override
    public Uni<List<ExecutorInfo>> getHealthyExecutors(ExecutorPlacementRequirements placement) {
        ExecutorPlacementRequirements effectivePlacement = placement != null
                ? placement
                : ExecutorPlacementRequirements.none();
        List<ExecutorInfo> healthyExecutors = executors.values().stream()
                .filter(this::isHealthyNow)
                .filter(executor -> isPlacementCompatible(executor, effectivePlacement))
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
        return heartbeat(executorId, -1);
    }

    @Override
    public Uni<Void> heartbeat(String executorId, int currentTaskCount) {
        ExecutorHealthInfo health = healthInfo.get(executorId);
        if (health != null) {
            if (currentTaskCount >= 0) {
                health.updateHeartbeat(currentTaskCount);
            } else {
                health.updateHeartbeat();
            }
            LOG.debug("Heartbeat updated for executor: {}, currentTaskCount={}",
                    executorId, health.taskCount);
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

                return executor.withEndpoint(discoveredEndpoint.get());
            }
        }
        return executor;
    }

    @Override
    public Uni<List<ExecutorInfo>> getExecutorsByType(String executorType) {
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executorTypeMatches(executor, executorType))
                .collect(Collectors.toList());
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<List<ExecutorInfo>> getExecutorsByType(
            String executorType,
            ExecutorPlacementRequirements placement) {
        ExecutorPlacementRequirements effectivePlacement = placement != null
                ? placement
                : ExecutorPlacementRequirements.none();
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executorTypeMatches(executor, executorType))
                .filter(executor -> isPlacementCompatible(executor, effectivePlacement))
                .collect(Collectors.toList());
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<List<ExecutorInfo>> getHealthyExecutorsByType(
            String executorType,
            ExecutorPlacementRequirements placement) {
        ExecutorPlacementRequirements effectivePlacement = placement != null
                ? placement
                : ExecutorPlacementRequirements.none();
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executorTypeMatches(executor, executorType))
                .filter(this::isHealthyNow)
                .filter(executor -> isPlacementCompatible(executor, effectivePlacement))
                .collect(Collectors.toList());
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<Optional<ExecutorInfo>> getExecutorForNodeByType(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement) {
        if (executorType == null || executorType.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return selectExecutor(ExecutorSelectionRequest.forNodeType(nodeId, executorType, placement));
    }

    @Override
    public Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(CommunicationType communicationType) {
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executor.communicationType() == communicationType)
                .collect(Collectors.toList());
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(
            CommunicationType communicationType,
            ExecutorPlacementRequirements placement) {
        ExecutorPlacementRequirements effectivePlacement = placement != null
                ? placement
                : ExecutorPlacementRequirements.none();
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executor.communicationType() == communicationType)
                .filter(executor -> isPlacementCompatible(executor, effectivePlacement))
                .collect(Collectors.toList());
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<Void> updateExecutorMetadata(String executorId, Map<String, String> metadata) {
        ExecutorInfo executor = executors.get(executorId);
        if (executor != null) {
            ExecutorInfo updatedExecutor = executor.withMetadata(metadata);
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

    private Optional<ExecutorInfo> selectBestExecutorForNode(
            NodeId nodeId,
            List<ExecutorInfo> availableExecutors,
            ExecutorPlacementRequirements placement,
            Map<String, Object> additionalContext,
            ExecutorSelectionStrategy strategy) {
        if (availableExecutors.isEmpty()) {
            LOG.warn("No healthy executors available for node: {}", nodeId.value());
            return Optional.empty();
        }

        if (preferLocalSelection) {
            Optional<ExecutorInfo> local = availableExecutors.stream()
                    .filter(executor -> executor.communicationType() == CommunicationType.LOCAL)
                    .findFirst();
            if (local.isPresent()) {
                LOG.debug("Selected local executor {} for node {}",
                        local.get().executorId(), nodeId.value());
                return local;
            }
        }

        ExecutorSelectionStrategy effectiveStrategy = strategy != null ? strategy : defaultStrategy;
        Optional<ExecutorInfo> selected = effectiveStrategy.select(
                nodeId,
                availableExecutors,
                selectionContext(placement, additionalContext, availableExecutors));

        if (selected.isPresent()) {
            LOG.debug("Selected executor {} for node {} using {} strategy",
                    selected.get().executorId(), nodeId.value(), effectiveStrategy.getName());
        } else {
            LOG.warn("No executor could be selected for node: {}", nodeId.value());
        }

        return selected;
    }

    private Map<String, Object> selectionContext(
            ExecutorPlacementRequirements placement,
            Map<String, Object> additionalContext,
            List<ExecutorInfo> availableExecutors) {
        Map<String, Object> context = additionalContext == null || additionalContext.isEmpty()
                ? new HashMap<>()
                : new HashMap<>(additionalContext);
        if (placement != null && !placement.isEmpty()) {
            context.put("placement", placement.toContextMap());
        }
        Map<String, Integer> taskCounts = taskCounts(availableExecutors);
        if (!taskCounts.isEmpty()) {
            context.put(LeastLoadedSelectionStrategy.CONTEXT_TASK_COUNTS, taskCounts);
        }
        if (context.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(context);
    }

    private Map<String, Integer> taskCounts(List<ExecutorInfo> availableExecutors) {
        if (availableExecutors == null || availableExecutors.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> taskCounts = new HashMap<>();
        for (ExecutorInfo executor : availableExecutors) {
            if (executor == null) {
                continue;
            }
            ExecutorHealthInfo health = healthInfo.get(executor.executorId());
            if (health != null) {
                taskCounts.put(executor.executorId(), Math.max(0, health.taskCount));
            }
        }
        return taskCounts.isEmpty() ? Map.of() : Map.copyOf(taskCounts);
    }

    private static Map<String, Object> additionalSelectionContext(ExecutorSelectionRequest request) {
        Map<String, Object> context = request.selectionContext().isEmpty()
                ? new HashMap<>()
                : new HashMap<>(request.selectionContext());
        if (request.hasExecutorType()) {
            context.put("executorType", request.executorType());
        }
        if (request.hasSelectionStrategy()) {
            context.put(ExecutorSelectionRequest.SELECTION_STRATEGY_KEY, request.selectionStrategy());
        }
        context.putAll(request.capabilityRequirements().toContextMap());
        context.putAll(request.resourceRequirements().toContextMap());
        return context.isEmpty() ? Map.of() : Map.copyOf(context);
    }

    private ExecutorSelectionStrategy selectionStrategyFor(ExecutorSelectionRequest request) {
        if (!request.hasSelectionStrategy()) {
            return defaultStrategy;
        }
        return selectionStrategyByName(request.selectionStrategy())
                .orElseGet(() -> {
                    LOG.warn("Unknown executor selection strategy '{}', falling back to {}",
                            request.selectionStrategy(),
                            defaultStrategy.getName());
                    return defaultStrategy;
                });
    }

    private Optional<ExecutorSelectionStrategy> selectionStrategyByName(String strategyName) {
        String normalizedName = normalizeStrategyName(strategyName);
        if (normalizedName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectionStrategies.get(normalizedName));
    }

    private static String normalizeStrategyName(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) {
            return null;
        }
        return strategyName.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> selectionDiagnosticsContext(
            ExecutorSelectionRequest request,
            ExecutorSelectionStrategy appliedStrategy,
            SelectionDiagnostics diagnostics) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("configuredDefaultStrategy", defaultStrategy.getName());
        context.put("appliedStrategy", appliedStrategy.getName());
        context.put("preferLocal", preferLocalSelection);
        context.put(
                "filterOrder",
                List.of(
                        "executor-type",
                        "health",
                        "placement",
                        "capability-hard-constraints",
                        "resource-constraints",
                        "capacity-constraints"));
        if (diagnostics.invalidCapacityMetadataExecutors() > 0) {
            context.put("invalidCapacityMetadataExecutors", diagnostics.invalidCapacityMetadataExecutors());
            context.put("capacityMetadataKey", LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS);
        }
        if (diagnostics.saturatedExecutors() > 0) {
            context.put("saturatedExecutors", diagnostics.saturatedExecutors());
            context.put("capacityMetadataKey", LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS);
        }
        if (request.hasSelectionStrategy()) {
            context.put("requestedStrategy", request.selectionStrategy());
            if (!selectionStrategyByName(request.selectionStrategy()).isPresent()) {
                context.put("strategyFallback", "unknown-requested-strategy");
            }
        }
        if (request.hasRequiredCapabilities()) {
            context.put("requiredCapabilities", request.requiredCapabilities().stream().sorted().toList());
        }
        if (request.hasPreferredCapabilities()) {
            long preferredMatches = diagnostics.candidates().stream()
                    .filter(request.capabilityRequirements()::preferredBy)
                    .count();
            context.put("preferredCapabilities", request.preferredCapabilities().stream().sorted().toList());
            context.put("preferredCapabilityMatches", preferredMatches);
            context.put("preferredCapabilityBiasApplied", preferredMatches > 0);
        }
        if (request.hasExcludedCapabilities()) {
            context.put("excludedCapabilities", request.excludedCapabilities().stream().sorted().toList());
        }
        if (request.hasResourceRequirements()) {
            context.put("resourceRequirements", request.resourceRequirements().toContextMap());
        }
        context.put("registeredStrategies", selectionStrategies.keySet().stream().sorted().toList());
        return Map.copyOf(context);
    }

    private SelectionDiagnostics evaluateSelectionCandidates(ExecutorSelectionRequest request) {
        List<String> cachedExecutorIds = nodeExecutorCache.get(request.nodeId());
        List<ExecutorInfo> candidates = new ArrayList<>();
        List<ExecutorInfo> cachedCandidates = new ArrayList<>();
        Map<String, Integer> rejectionCounts = new LinkedHashMap<>();
        int totalExecutors = 0;
        int typeCompatibleExecutors = 0;
        int healthyExecutors = 0;
        int placementCompatibleExecutors = 0;
        int invalidCapacityMetadataExecutors = 0;
        int saturatedExecutors = 0;

        for (ExecutorInfo executor : executors.values()) {
            totalExecutors++;
            if (executor == null) {
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.MISSING_EXECUTOR);
                continue;
            }

            if (request.hasExecutorType() && !executorTypeMatches(executor, request.executorType())) {
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.EXECUTOR_TYPE_MISMATCH);
                continue;
            }
            typeCompatibleExecutors++;

            boolean healthy = isHealthyNow(executor);
            if (healthy) {
                healthyExecutors++;
            }
            if (request.requireHealthy() && !healthy) {
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.UNHEALTHY);
                continue;
            }

            PlacementCompatibility placement = placementCompatibility(executor, request.placement());
            if (placement == PlacementCompatibility.INVALID_METADATA) {
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.INVALID_PLACEMENT_METADATA);
                continue;
            }
            if (placement == PlacementCompatibility.MISMATCH) {
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.PLACEMENT_MISMATCH);
                continue;
            }

            placementCompatibleExecutors++;
            ExecutorCapabilityRequirements.CapabilityMatch capabilityMatch =
                    request.capabilityRequirements().evaluate(executor);
            if (!capabilityMatch.matched()) {
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.CAPABILITY_MISMATCH);
                if (!capabilityMatch.missingRequiredCapabilities().isEmpty()) {
                    increment(rejectionCounts, ExecutorSelectionRejectionReasons.REQUIRED_CAPABILITY_MISMATCH);
                }
                if (!capabilityMatch.matchedExcludedCapabilities().isEmpty()) {
                    increment(rejectionCounts, ExecutorSelectionRejectionReasons.EXCLUDED_CAPABILITY_PRESENT);
                }
                continue;
            }

            ExecutorResourceRequirements.ResourceMatch resourceMatch =
                    request.resourceRequirements().evaluate(executor);
            if (!resourceMatch.matched()) {
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.RESOURCE_MISMATCH);
                if (!resourceMatch.missingMetadataKeys().isEmpty()) {
                    increment(rejectionCounts, ExecutorSelectionRejectionReasons.RESOURCE_MISSING_METADATA);
                }
                if (!resourceMatch.invalidMetadataKeys().isEmpty()) {
                    increment(rejectionCounts, ExecutorSelectionRejectionReasons.RESOURCE_INVALID_METADATA);
                }
                if (!resourceMatch.insufficientResourceKeys().isEmpty()) {
                    increment(rejectionCounts, ExecutorSelectionRejectionReasons.RESOURCE_INSUFFICIENT);
                }
                if (!resourceMatch.mismatchedLocalityKeys().isEmpty()) {
                    increment(rejectionCounts, ExecutorSelectionRejectionReasons.RESOURCE_LOCALITY_MISMATCH);
                }
                continue;
            }

            ExecutorLoadSupport.CapacityLimit capacityLimit = ExecutorLoadSupport.capacityLimit(executor);
            if (!capacityLimit.valid()) {
                invalidCapacityMetadataExecutors++;
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA);
                continue;
            }

            if (ExecutorLoadSupport.isSaturated(capacityLimit, healthInfo.get(executor.executorId()))) {
                saturatedExecutors++;
                increment(rejectionCounts, ExecutorSelectionRejectionReasons.CAPACITY_SATURATED);
                continue;
            }

            candidates.add(executor);
            if (cachedExecutorIds != null && cachedExecutorIds.contains(executor.executorId())) {
                cachedCandidates.add(executor);
            }
        }

        return new SelectionDiagnostics(
                candidates,
                cachedCandidates,
                totalExecutors,
                cachedExecutorIds != null ? cachedExecutorIds.size() : 0,
                typeCompatibleExecutors,
                healthyExecutors,
                placementCompatibleExecutors,
                invalidCapacityMetadataExecutors,
                saturatedExecutors,
                rejectionCounts);
    }

    private static void increment(Map<String, Integer> counts, String reason) {
        counts.merge(reason, 1, Integer::sum);
    }

    private boolean isPlacementCompatible(
            ExecutorInfo executor,
            ExecutorPlacementRequirements placement) {
        return placementCompatibility(executor, placement) == PlacementCompatibility.MATCH;
    }

    private PlacementCompatibility placementCompatibility(
            ExecutorInfo executor,
            ExecutorPlacementRequirements placement) {
        try {
            if (placement == null || placement.matches(executor)) {
                return PlacementCompatibility.MATCH;
            }
            return PlacementCompatibility.MISMATCH;
        } catch (GamelanException error) {
            LOG.warn("Executor {} has invalid placement metadata and will be skipped: {}",
                    executor != null ? executor.executorId() : "<null>",
                    error.getSafeMessage());
            return PlacementCompatibility.INVALID_METADATA;
        }
    }

    private boolean executorTypeMatches(ExecutorInfo executor, String executorType) {
        return executor != null && java.util.Objects.equals(executor.executorType(), executorType);
    }

    private static List<ExecutorInfo> applyPreferredCapabilityBias(
            ExecutorCapabilityRequirements requirements,
            List<ExecutorInfo> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (requirements == null || !requirements.hasPreferredCapabilities()) {
            return candidates;
        }

        List<ExecutorInfo> preferredCandidates = candidates.stream()
                .filter(requirements::preferredBy)
                .toList();
        return preferredCandidates.isEmpty() ? candidates : preferredCandidates;
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

    private record SelectionDiagnostics(
            List<ExecutorInfo> candidates,
            List<ExecutorInfo> cachedCandidates,
            int totalExecutors,
            int cachedExecutors,
            int typeCompatibleExecutors,
            int healthyExecutors,
            int placementCompatibleExecutors,
            int invalidCapacityMetadataExecutors,
            int saturatedExecutors,
            Map<String, Integer> rejectionCounts) {
    }

    private enum PlacementCompatibility {
        MATCH,
        MISMATCH,
        INVALID_METADATA
    }

    /**
     * Set the selection strategy to use
     */
    public void setSelectionStrategy(ExecutorSelectionStrategy strategy) {
        registerSelectionStrategy(strategy);
        this.defaultStrategy = strategy;
        LOG.info("Set executor selection strategy to: {}", strategy.getName());
    }

    /**
     * Register a named selection strategy for per-request overrides.
     */
    public final void registerSelectionStrategy(ExecutorSelectionStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("selection strategy cannot be null");
        }
        String normalizedName = normalizeStrategyName(strategy.getName());
        if (normalizedName == null) {
            throw new IllegalArgumentException("selection strategy name cannot be blank");
        }
        selectionStrategies.put(normalizedName, strategy);
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
