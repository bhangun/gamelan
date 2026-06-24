package tech.kayys.gamelan.registry;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPABILITY_MISMATCH;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.EXCLUDED_CAPABILITY_PRESENT;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.EXECUTOR_TYPE_MISMATCH;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.INVALID_PLACEMENT_METADATA;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.PLACEMENT_MISMATCH;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.REQUIRED_CAPABILITY_MISMATCH;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.RESOURCE_INSUFFICIENT;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.RESOURCE_INVALID_METADATA;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.RESOURCE_LOCALITY_MISMATCH;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.RESOURCE_MISMATCH;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.RESOURCE_MISSING_METADATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.collaboration.ParticipantIsolation;
import tech.kayys.gamelan.engine.collaboration.ParticipantKind;
import tech.kayys.gamelan.engine.collaboration.ParticipantRuntime;
import tech.kayys.gamelan.engine.executor.ExecutorHealthInfo;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorResourceRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.registry.metrics.RegistryMetricsService;
import tech.kayys.gamelan.registry.persistence.InMemoryExecutorRepository;

class ExecutorRegistryPlacementTest {

    private ExecutorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExecutorRegistry();
        registry.healthThreshold = Duration.ofMinutes(5);
        registry.executorRepository = new InMemoryExecutorRepository();
        registry.metricsService = new RegistryMetricsService();
    }

    @Test
    void getExecutorForNodeSelectsPlacementCompatibleExecutor() {
        registry.registerExecutor(executor("local-only", CommunicationType.LOCAL, Map.of()))
                .await().indefinitely();
        registry.registerExecutor(executor(
                "sandboxed-distributed",
                CommunicationType.GRPC,
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "remote,distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();

        Optional<ExecutorInfo> selected = registry.getExecutorForNode(
                NodeId.of("agent-node"),
                distributedSandboxPlacement())
                .await().indefinitely();

        assertTrue(selected.isPresent());
        assertEquals("sandboxed-distributed", selected.get().executorId());
    }

    @Test
    void getExecutorForNodeSkipsInvalidPlacementMetadata() {
        registry.registerExecutor(executor(
                "bad-metadata",
                CommunicationType.GRPC,
                Map.of(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "telepathy")))
                .await().indefinitely();
        registry.registerExecutor(executor(
                "sandboxed-distributed",
                CommunicationType.GRPC,
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();

        Optional<ExecutorInfo> selected = registry.getExecutorForNode(
                NodeId.of("agent-node"),
                distributedSandboxPlacement())
                .await().indefinitely();

        assertTrue(selected.isPresent());
        assertEquals("sandboxed-distributed", selected.get().executorId());
    }

    @Test
    void getExecutorsByTypeFiltersPlacementAndType() {
        registry.registerExecutor(executor("local-agent", CommunicationType.LOCAL, Map.of()))
                .await().indefinitely();
        registry.registerExecutor(executor(
                "sandboxed-agent",
                CommunicationType.GRPC,
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();
        registry.registerExecutor(executor(
                "sandboxed-human",
                "human-task",
                CommunicationType.GRPC,
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();

        List<ExecutorInfo> selected = registry.getExecutorsByType("agent", distributedSandboxPlacement())
                .await().indefinitely();
        List<ExecutorInfo> healthySelected = registry.getHealthyExecutorsByType(
                "agent",
                distributedSandboxPlacement())
                .await().indefinitely();
        Optional<ExecutorInfo> selectedExecutor = registry.getExecutorForNodeByType(
                NodeId.of("agent-node"),
                "agent",
                distributedSandboxPlacement())
                .await().indefinitely();

        assertEquals(1, selected.size());
        assertEquals("sandboxed-agent", selected.getFirst().executorId());
        assertEquals(1, healthySelected.size());
        assertEquals("sandboxed-agent", healthySelected.getFirst().executorId());
        assertTrue(selectedExecutor.isPresent());
        assertEquals("sandboxed-agent", selectedExecutor.get().executorId());
    }

    @Test
    void selectExecutorUsesSelectionRequestEnvelope() {
        registry.registerExecutor(executor("local-agent", CommunicationType.LOCAL, Map.of()))
                .await().indefinitely();
        registry.registerExecutor(executor(
                "sandboxed-agent",
                CommunicationType.GRPC,
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();

        Optional<ExecutorInfo> selected = registry.selectExecutor(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                distributedSandboxPlacement()))
                .await().indefinitely();

        assertTrue(selected.isPresent());
        assertEquals("sandboxed-agent", selected.get().executorId());
    }

    @Test
    void selectExecutorWithDiagnosticsExplainsRejectedExecutors() {
        registry.registerExecutor(executor("local-agent", CommunicationType.LOCAL, Map.of()))
                .await().indefinitely();
        registry.registerExecutor(executor(
                "sandboxed-human",
                "human-task",
                CommunicationType.GRPC,
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();
        registry.registerExecutor(executor(
                "bad-metadata",
                CommunicationType.GRPC,
                Map.of(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "telepathy")))
                .await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                distributedSandboxPlacement()))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isEmpty());
        assertEquals(3, report.totalExecutors());
        assertEquals(2, report.typeCompatibleExecutors());
        assertEquals(2, report.healthyExecutors());
        assertEquals(0, report.placementCompatibleExecutors());
        assertEquals(0, report.candidateExecutors());
        assertEquals(1, report.rejectionCounts().get(EXECUTOR_TYPE_MISMATCH));
        assertEquals(1, report.rejectionCounts().get(PLACEMENT_MISMATCH));
        assertEquals(1, report.rejectionCounts().get(INVALID_PLACEMENT_METADATA));
        assertEquals(0, report.toErrorContext().get("candidateExecutors"));
        assertTrue(report.toErrorContext().containsKey("placement"));
    }

    @Test
    void selectExecutorCanUsePerRequestStrategyOverride() {
        registry.registerSelectionStrategy(new ExecutorSelectionStrategy() {
            @Override
            public Optional<ExecutorInfo> select(
                    NodeId nodeId,
                    List<ExecutorInfo> availableExecutors,
                    Map<String, Object> context) {
                return availableExecutors.stream()
                        .max(java.util.Comparator.comparing(ExecutorInfo::executorId));
            }

            @Override
            public String getName() {
                return "highest-id";
            }
        });
        registry.registerExecutor(executor("agent-a", CommunicationType.GRPC, Map.of(
                ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "distributed",
                ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();
        registry.registerExecutor(executor("agent-z", CommunicationType.GRPC, Map.of(
                ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "distributed",
                ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "sandbox")))
                .await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                distributedSandboxPlacement(),
                Map.of(ExecutorSelectionRequest.STRATEGY_KEY, "highest-id")))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("agent-z", report.selectedExecutor().get().executorId());
        assertEquals("highest-id", report.request().selectionStrategy());
        assertEquals("highest-id", report.diagnostics().get("appliedStrategy"));
        assertEquals("highest-id", report.diagnostics().get("requestedStrategy"));
    }

    @Test
    void selectExecutorCanUseLeastLoadedHeartbeatStrategy() {
        registry.registerExecutor(executor("busy-agent", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "10")))
                .await().indefinitely();
        registry.registerExecutor(executor("idle-agent", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "10")))
                .await().indefinitely();
        registry.heartbeat("busy-agent", 7).await().indefinitely();
        registry.heartbeat("idle-agent", 1).await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(ExecutorSelectionRequest.STRATEGY_KEY, "least-loaded")))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("idle-agent", report.selectedExecutor().get().executorId());
        assertEquals("least-loaded", report.diagnostics().get("appliedStrategy"));
        assertTrue(((List<?>) report.diagnostics().get("registeredStrategies")).contains("least-loaded"));
    }

    @Test
    void selectExecutorCanUseWeightedHeartbeatStrategy() {
        registry.registerExecutor(executor("small-agent", CommunicationType.GRPC, Map.of(
                WeightedSelectionStrategy.METADATA_SELECTION_WEIGHT, "2")))
                .await().indefinitely();
        registry.registerExecutor(executor("large-agent", CommunicationType.GRPC, Map.of(
                WeightedSelectionStrategy.METADATA_SELECTION_WEIGHT, "10")))
                .await().indefinitely();
        registry.heartbeat("small-agent", 1).await().indefinitely();
        registry.heartbeat("large-agent", 3).await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(ExecutorSelectionRequest.STRATEGY_KEY, "weighted")))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("large-agent", report.selectedExecutor().get().executorId());
        assertEquals("weighted", report.diagnostics().get("appliedStrategy"));
        assertTrue(((List<?>) report.diagnostics().get("registeredStrategies")).contains("weighted"));
    }

    @Test
    void selectExecutorRejectsSaturatedExecutors() {
        registry.registerExecutor(executor("full-agent", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "2")))
                .await().indefinitely();
        registry.registerExecutor(executor("available-agent", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "4")))
                .await().indefinitely();
        registry.heartbeat("full-agent", 2).await().indefinitely();
        registry.heartbeat("available-agent", 3).await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(ExecutorSelectionRequest.STRATEGY_KEY, "least-loaded")))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("available-agent", report.selectedExecutor().get().executorId());
        assertEquals(1, report.candidateExecutors());
        assertEquals(1, report.rejectionCounts().get(CAPACITY_SATURATED));
        assertEquals(CAPACITY_SATURATED, report.primaryRejectionReason());
        assertEquals(CAPACITY_SATURATED, report.toErrorContext().get("primaryRejectionReason"));
        assertEquals(1, report.diagnostics().get("saturatedExecutors"));
    }

    @Test
    void selectExecutorReturnsEmptyWhenAllCapacityAwareExecutorsAreSaturated() {
        registry.registerExecutor(executor("full-agent-a", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "1")))
                .await().indefinitely();
        registry.registerExecutor(executor("full-agent-b", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "2")))
                .await().indefinitely();
        registry.heartbeat("full-agent-a", 1).await().indefinitely();
        registry.heartbeat("full-agent-b", 3).await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(ExecutorSelectionRequest.STRATEGY_KEY, "least-loaded")))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isEmpty());
        assertEquals(0, report.candidateExecutors());
        assertEquals(2, report.rejectionCounts().get(CAPACITY_SATURATED));
        assertEquals(CAPACITY_SATURATED, report.primaryRejectionReason());
        assertEquals(2, report.diagnostics().get("saturatedExecutors"));
    }

    @Test
    void selectExecutorRejectsInvalidCapacityMetadata() {
        registry.registerExecutor(executor("invalid-agent", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "many")))
                .await().indefinitely();
        registry.registerExecutor(executor("zero-agent", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "0")))
                .await().indefinitely();
        registry.registerExecutor(executor("available-agent", CommunicationType.GRPC, Map.of(
                LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS, "2")))
                .await().indefinitely();
        registry.heartbeat("invalid-agent", 0).await().indefinitely();
        registry.heartbeat("zero-agent", 0).await().indefinitely();
        registry.heartbeat("available-agent", 1).await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(ExecutorSelectionRequest.STRATEGY_KEY, "least-loaded")))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("available-agent", report.selectedExecutor().get().executorId());
        assertEquals(1, report.candidateExecutors());
        assertEquals(2, report.rejectionCounts().get(INVALID_CAPACITY_METADATA));
        assertEquals(INVALID_CAPACITY_METADATA, report.primaryRejectionReason());
        assertEquals(true, report.hasPermanentRejection());
        assertEquals(2, report.diagnostics().get("invalidCapacityMetadataExecutors"));
    }

    @Test
    void selectExecutorFiltersRequiredCapabilities() {
        registry.registerExecutor(executor("general-agent", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "chat summary")))
                .await().indefinitely();
        registry.registerExecutor(executor("coding-agent", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "chat CODING Sandbox")))
                .await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(ExecutorSelectionRequest.REQUIRED_CAPABILITIES_KEY, List.of(" Coding ", "SANDBOX"))))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("coding-agent", report.selectedExecutor().get().executorId());
        assertEquals(Set.of("coding", "sandbox"), report.request().requiredCapabilities());
        assertEquals(1, report.rejectionCounts().get(CAPABILITY_MISMATCH));
        assertEquals(1, report.rejectionCounts().get(REQUIRED_CAPABILITY_MISMATCH));
        assertEquals(2, report.placementCompatibleExecutors());
        assertEquals(1, report.candidateExecutors());
        assertTrue(((List<?>) report.diagnostics().get("requiredCapabilities")).contains("coding"));
        assertTrue(((List<?>) report.toErrorContext().get("requiredCapabilities")).contains("sandbox"));
    }

    @Test
    void selectExecutorFiltersExcludedCapabilities() {
        registry.registerExecutor(executor("finance-agent", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "coding sandbox finance")))
                .await().indefinitely();
        registry.registerExecutor(executor("safe-agent", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "coding sandbox")))
                .await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(
                        ExecutorSelectionRequest.REQUIRED_CAPABILITIES_KEY, List.of("coding"),
                        ExecutorSelectionRequest.EXCLUDED_CAPABILITIES_KEY, List.of("finance"))))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("safe-agent", report.selectedExecutor().get().executorId());
        assertEquals(Set.of("finance"), report.request().excludedCapabilities());
        assertEquals(1, report.rejectionCounts().get(CAPABILITY_MISMATCH));
        assertEquals(1, report.rejectionCounts().get(EXCLUDED_CAPABILITY_PRESENT));
        assertTrue(((List<?>) report.toErrorContext().get("excludedCapabilities")).contains("finance"));
    }

    @Test
    void selectExecutorBiasesPreferredCapabilitiesBeforeStrategy() {
        registry.registerExecutor(executor("coding-agent", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "coding sandbox")))
                .await().indefinitely();
        registry.registerExecutor(executor("browser-agent", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "coding sandbox browser")))
                .await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(
                        ExecutorSelectionRequest.REQUIRED_CAPABILITIES_KEY, List.of("coding"),
                        ExecutorSelectionRequest.PREFERRED_CAPABILITIES_KEY, List.of("browser"))))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("browser-agent", report.selectedExecutor().get().executorId());
        assertEquals(2, report.candidateExecutors());
        assertEquals(Set.of("browser"), report.request().preferredCapabilities());
        assertEquals(1L, report.diagnostics().get("preferredCapabilityMatches"));
        assertEquals(true, report.diagnostics().get("preferredCapabilityBiasApplied"));
        assertTrue(((List<?>) report.toErrorContext().get("preferredCapabilities")).contains("browser"));
    }

    @Test
    void selectExecutorFiltersResourceRequirements() {
        registry.registerExecutor(executor("small-us-agent", CommunicationType.GRPC, Map.of(
                ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY, "2048",
                ExecutorResourceRequirements.METADATA_CPU_CORES_KEY, "4",
                ExecutorResourceRequirements.METADATA_REGIONS_KEY, "us-east-1",
                ExecutorResourceRequirements.METADATA_DATA_RESIDENCIES_KEY, "us")))
                .await().indefinitely();
        registry.registerExecutor(executor("large-eu-agent", CommunicationType.GRPC, Map.of(
                ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY, "8192",
                ExecutorResourceRequirements.METADATA_CPU_CORES_KEY, "4",
                ExecutorResourceRequirements.METADATA_REGIONS_KEY, "eu-west-1",
                ExecutorResourceRequirements.METADATA_DATA_RESIDENCIES_KEY, "eu")))
                .await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(
                        ExecutorSelectionRequest.MIN_MEMORY_MB_KEY, 4096,
                        ExecutorSelectionRequest.MIN_CPU_CORES_KEY, "2",
                        ExecutorSelectionRequest.REGIONS_KEY, List.of("eu-west-1"),
                        ExecutorSelectionRequest.DATA_RESIDENCY_KEY, "eu")))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isPresent());
        assertEquals("large-eu-agent", report.selectedExecutor().get().executorId());
        assertEquals(4096L, report.request().resourceRequirements().minMemoryMb());
        assertEquals(1, report.rejectionCounts().get(RESOURCE_MISMATCH));
        assertEquals(1, report.rejectionCounts().get(RESOURCE_INSUFFICIENT));
        assertEquals(1, report.rejectionCounts().get(RESOURCE_LOCALITY_MISMATCH));
        assertEquals(1, report.candidateExecutors());
        assertTrue(((Map<?, ?>) report.toErrorContext().get("resourceRequirements"))
                .containsKey(ExecutorSelectionRequest.MIN_MEMORY_MB_KEY));
    }

    @Test
    void selectExecutorRejectsMissingAndInvalidResourceMetadata() {
        registry.registerExecutor(executor("missing-resource-agent", CommunicationType.GRPC, Map.of()))
                .await().indefinitely();
        registry.registerExecutor(executor("invalid-resource-agent", CommunicationType.GRPC, Map.of(
                ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY, "many",
                ExecutorResourceRequirements.METADATA_CPU_CORES_KEY, "2")))
                .await().indefinitely();

        ExecutorSelectionReport report = registry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(
                        ExecutorSelectionRequest.MIN_MEMORY_MB_KEY, 4096,
                        ExecutorSelectionRequest.MIN_CPU_CORES_KEY, 1)))
                .await().indefinitely();

        assertTrue(report.selectedExecutor().isEmpty());
        assertEquals(2, report.rejectionCounts().get(RESOURCE_MISMATCH));
        assertEquals(1, report.rejectionCounts().get(RESOURCE_MISSING_METADATA));
        assertEquals(1, report.rejectionCounts().get(RESOURCE_INVALID_METADATA));
    }

    @Test
    void getExecutorForNodeByTypePrefersLocalWhenConfigured() {
        registry.preferLocalSelection = true;
        registry.registerExecutor(executor("remote-agent", CommunicationType.GRPC, Map.of()))
                .await().indefinitely();
        registry.registerExecutor(executor("local-agent", CommunicationType.LOCAL, Map.of()))
                .await().indefinitely();

        Optional<ExecutorInfo> selected = registry.getExecutorForNodeByType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none())
                .await().indefinitely();

        assertTrue(selected.isPresent());
        assertEquals("local-agent", selected.get().executorId());
    }

    @Test
    void defaultServiceTypePlacementLookupSkipsInvalidPlacementMetadata() {
        ExecutorRegistryService registryService = new DefaultOnlyRegistryService(executor(
                "bad-metadata",
                CommunicationType.GRPC,
                Map.of(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "telepathy")));

        List<ExecutorInfo> selected = registryService.getExecutorsByType("agent", distributedSandboxPlacement())
                .await().indefinitely();

        assertTrue(selected.isEmpty());
    }

    @Test
    void defaultServicePlacementLookupSkipsInvalidPlacementMetadata() {
        ExecutorRegistryService registryService = new DefaultOnlyRegistryService(executor(
                "bad-metadata",
                CommunicationType.GRPC,
                Map.of(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "telepathy")));

        Optional<ExecutorInfo> selected = registryService.getExecutorForNode(
                NodeId.of("agent-node"),
                distributedSandboxPlacement())
                .await().indefinitely();

        assertTrue(selected.isEmpty());
    }

    @Test
    void defaultServiceAppliesPreferredCapabilitiesAfterResourceFilters() {
        ExecutorInfo preferredButSmall = executor("preferred-small", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "coding browser",
                ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY, "1024"));
        ExecutorInfo fallbackLarge = executor("fallback-large", CommunicationType.GRPC, Map.of(
                ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, "coding",
                ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY, "8192"));
        ExecutorRegistryService registryService = new DefaultOnlyRegistryService(preferredButSmall, fallbackLarge);

        Optional<ExecutorInfo> selected = registryService.selectExecutor(ExecutorSelectionRequest.forNodeType(
                NodeId.of("agent-node"),
                "agent",
                ExecutorPlacementRequirements.none(),
                Map.of(
                        ExecutorSelectionRequest.REQUIRED_CAPABILITIES_KEY, List.of("coding"),
                        ExecutorSelectionRequest.PREFERRED_CAPABILITIES_KEY, List.of("browser"),
                        ExecutorSelectionRequest.MIN_MEMORY_MB_KEY, 4096)))
                .await().indefinitely();

        assertTrue(selected.isPresent());
        assertEquals("fallback-large", selected.get().executorId());
    }

    @Test
    void selectionRequestNormalizesDefaults() {
        ExecutorSelectionRequest request = new ExecutorSelectionRequest(
                NodeId.of("agent-node"),
                " agent ",
                null,
                true,
                Map.of(
                        "domain", "support",
                        ExecutorSelectionRequest.SELECTION_STRATEGY_KEY, " weighted "));

        assertEquals("agent", request.executorType());
        assertTrue(request.placement().isEmpty());
        assertEquals("weighted", request.selectionStrategy());
        assertTrue(request.requiredCapabilities().isEmpty());
        assertTrue(request.resourceRequirements().isEmpty());
        assertEquals("support", request.selectionContext().get("domain"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.selectionContext().put("domain", "finance"));
    }

    @Test
    void selectionRequestNormalizesExplicitCapabilities() {
        ExecutorSelectionRequest request = new ExecutorSelectionRequest(
                NodeId.of("agent-node"),
                "agent",
                null,
                true,
                null,
                Set.of(" Coding ", "SANDBOX"),
                Map.of());

        assertEquals(Set.of("coding", "sandbox"), request.requiredCapabilities());
    }

    private static ExecutorPlacementRequirements distributedSandboxPlacement() {
        return new ExecutorPlacementRequirements(
                Set.of(ParticipantRuntime.DISTRIBUTED),
                Set.of(ParticipantIsolation.SANDBOX),
                Set.of(ParticipantKind.AGENT),
                Set.of());
    }

    private static ExecutorInfo executor(
            String executorId,
            CommunicationType communicationType,
            Map<String, String> metadata) {
        return executor(executorId, "agent", communicationType, metadata);
    }

    private static ExecutorInfo executor(
            String executorId,
            String executorType,
            CommunicationType communicationType,
            Map<String, String> metadata) {
        return new ExecutorInfo(
                executorId,
                executorType,
                communicationType,
                "endpoint",
                Duration.ofSeconds(30),
                metadata);
    }

    private record DefaultOnlyRegistryService(List<ExecutorInfo> executors) implements ExecutorRegistryService {

        private DefaultOnlyRegistryService(ExecutorInfo... executors) {
            this(List.of(executors));
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorForNode(NodeId nodeId) {
            return Uni.createFrom().item(executors.stream().findFirst());
        }

        @Override
        public Uni<List<ExecutorInfo>> getAllExecutors() {
            return Uni.createFrom().item(executors);
        }

        @Override
        public Uni<List<ExecutorInfo>> getHealthyExecutors() {
            return getAllExecutors();
        }

        @Override
        public Uni<Void> registerExecutor(ExecutorInfo executor) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> unregisterExecutor(String executorId) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> heartbeat(String executorId) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Optional<ExecutorHealthInfo>> getHealthInfo(String executorId) {
            return Uni.createFrom().item(Optional.empty());
        }

        @Override
        public Uni<Boolean> isHealthy(String executorId) {
            return Uni.createFrom().item(true);
        }

        @Override
        public Uni<Optional<ExecutorInfo>> getExecutorById(String executorId) {
            return Uni.createFrom().item(executors.stream()
                    .filter(executor -> executor.executorId().equals(executorId))
                    .findFirst());
        }

        @Override
        public Uni<List<ExecutorInfo>> getExecutorsByType(String executorType) {
            return Uni.createFrom().item(
                    executors.stream()
                            .filter(executor -> executor.executorType().equals(executorType))
                            .toList());
        }

        @Override
        public Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(CommunicationType communicationType) {
            return getAllExecutors();
        }

        @Override
        public Uni<Void> updateExecutorMetadata(String executorId, Map<String, String> metadata) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Integer> getExecutorCount() {
            return Uni.createFrom().item(executors.size());
        }

        @Override
        public Uni<ExecutorStatistics> getStatistics() {
            return Uni.createFrom().item(new ExecutorStatistics(
                    executors.size(),
                    executors.size(),
                    0,
                    Map.of(),
                    Map.of(),
                    System.currentTimeMillis()));
        }
    }
}
