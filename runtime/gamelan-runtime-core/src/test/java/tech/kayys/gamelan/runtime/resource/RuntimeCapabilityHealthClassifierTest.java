package tech.kayys.gamelan.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisherDiagnostics;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityHealth.Severity;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityHealth.Status;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeComponent;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeProfile;

class RuntimeCapabilityHealthClassifierTest {

    @Test
    void classify_whenCoreComponentsAndPublishersAreAvailable_reportsReady() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("default", "default"),
                localContract(),
                List.of(component("taskQueue", true)),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.READY, health.status());
        assertTrue(health.ready());
        assertTrue(health.issues().isEmpty());
        assertEquals(Instant.EPOCH, health.observedAt());
    }

    @Test
    void classify_whenRequiredComponentIsMissing_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("default", "default"),
                localContract(),
                List.of(component("workflowRunRepository", false)),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "component-unavailable".equals(issue.code())
                        && issue.severity() == Severity.ERROR
                        && "workflowRunRepository".equals(issue.component())));
    }

    @Test
    void classify_whenPublisherDiagnosticsHaveFailures_reportsDegradedButReady() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("default", "default"),
                localContract(),
                List.of(component("taskQueue", true)),
                List.of(),
                List.of(EventPublisherDiagnostics.available(
                        "event-publisher",
                        Map.of("persistenceAppendFailures", 1L),
                        "database timeout",
                        Instant.EPOCH)),
                List.of(WorkflowRunWakeupPublisherDiagnostics.unavailable(
                        "wakeup-publisher",
                        "diagnostics disabled")),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.DEGRADED, health.status());
        assertTrue(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "event-publisher-failures".equals(issue.code())
                        && issue.severity() == Severity.WARN));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "event-publisher-last-failure".equals(issue.code())
                        && issue.severity() == Severity.INFO));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "wakeup-publisher-unavailable".equals(issue.code())
                        && issue.severity() == Severity.WARN));
    }

    @Test
    void classify_whenWorkflowPersistenceProfileDoesNotMatchRepositories_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "default"),
                localContract(),
                List.of(
                        component("workflowDefinitionRepository", "tech.example.InMemoryWorkflowDefinitionRepository"),
                        component("workflowRunRepository", "tech.example.InMemoryWorkflowRunRepository"),
                        component("executionHistoryRepository", "tech.example.InMemoryExecutionHistoryRepository")),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "workflow-persistence-profile-mismatch".equals(issue.code())
                        && issue.severity() == Severity.ERROR
                        && "workflowRunRepository".equals(issue.component())));
    }

    @Test
    void classify_whenConfiguredAgentContextStoreIsMissing_reportsDegraded() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("default", "file"),
                localContract(),
                List.of(component("taskQueue", true)),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.DEGRADED, health.status());
        assertTrue(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "agent-context-profile-missing".equals(issue.code())
                        && issue.severity() == Severity.WARN));
    }

    @Test
    void classify_whenDistributedContractUsesMemoryPersistence_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("memory", "default", "memory", true, "memory"),
                RuntimeCapabilityContracts.resolve("distributed", profile("memory", "default")),
                List.of(component("taskQueue", true)),
                List.of(),
                List.of(),
                List.of(),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-workflow-persistence-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-registry-persistence-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-event-publisher-required".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-wakeup-publisher-required".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-grpc-task-stream-broker-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenOfflineAgentContractDoesNotUseFileStores_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("memory", "default"),
                RuntimeCapabilityContracts.resolve("offline-agent", profile("memory", "default")),
                List.of(component("taskQueue", true)),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-workflow-persistence-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-agent-context-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractUsesLocalDurability_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("file", "file", "memory", false, "redis"),
                RuntimeCapabilityContracts.resolve("production", profile("file", "file")),
                List.of(component("taskQueue", true)),
                List.of(component("agentContextStore", "tech.example.FileAgentContextStore")),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-workflow-persistence-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-agent-context-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-registry-persistence-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractLacksConfiguredAgentContextImplementation_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "postgres", "database", false, "redis"),
                RuntimeCapabilityContracts.resolve("production", profile("postgres", "postgres")),
                List.of(
                        component(
                                "workflowDefinitionRepository",
                                "tech.example.PostgresWorkflowDefinitionRepository"),
                        component(
                                "workflowRunRepository",
                                "tech.example.PostgresWorkflowRunRepository"),
                        component(
                                "executionHistoryRepository",
                                "tech.example.PostgresExecutionHistoryRepository")),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-agent-context-implementation-missing".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractUsesWrongWorkflowImplementation_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "postgres", "database", false, "redis"),
                RuntimeCapabilityContracts.resolve("production", profile("postgres", "postgres")),
                List.of(
                        component(
                                "workflowDefinitionRepository",
                                "tech.example.InMemoryWorkflowDefinitionRepository"),
                        component(
                                "workflowRunRepository",
                                "tech.example.InMemoryWorkflowRunRepository"),
                        component(
                                "executionHistoryRepository",
                                "tech.example.InMemoryExecutionHistoryRepository")),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-workflow-persistence-implementation-mismatch"
                        .equals(issue.code())
                        && issue.severity() == Severity.ERROR
                        && "workflowRunRepository".equals(issue.component())));
    }

    @Test
    void classify_whenProductionContractUsesWrongGrpcBrokerImplementation_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "postgres", "database", false, "redis"),
                RuntimeCapabilityContracts.resolve("production", profile("postgres", "postgres")),
                postgresWorkflowComponents(),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(component("grpcTaskStreamBroker", "tech.example.InMemoryGrpcTaskStreamBroker")),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-grpc-task-stream-broker-implementation-mismatch"
                        .equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenStreamContractHasNoGrpcBrokerImplementation_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "postgres", "database", true, "redis"),
                RuntimeCapabilityContracts.resolve("production", profile("postgres", "postgres")),
                postgresWorkflowComponents(),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-grpc-task-stream-broker-implementation-missing"
                        .equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractUsesLocalSchedulerMode_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "postgres", "database", false, "redis", "local", true),
                RuntimeCapabilityContracts.resolve("production", profile("postgres", "postgres")),
                productionRuntimeComponents(
                        "tech.example.RedisTaskQueue",
                        "tech.example.PostgresTaskDeadLetterQueue",
                        "tech.example.RedisRetryManager"),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(component("grpcTaskStreamBroker", "tech.example.RedisGrpcTaskStreamBroker")),
                List.of(component("recoveryLeaseRepository", "tech.example.PostgresWorkflowRecoveryLeaseRepository")),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-scheduler-mode-violation".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractUsesLocalTaskRuntimeComponents_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "postgres", "database", false, "redis", "redis", true),
                RuntimeCapabilityContracts.resolve("production", profile("postgres", "postgres")),
                productionRuntimeComponents(
                        "tech.example.InMemoryTaskQueue",
                        "tech.example.PostgresTaskDeadLetterQueue",
                        "tech.example.InMemoryRetryManager"),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(component("grpcTaskStreamBroker", "tech.example.RedisGrpcTaskStreamBroker")),
                List.of(component("recoveryLeaseRepository", "tech.example.PostgresWorkflowRecoveryLeaseRepository")),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-task-queue-implementation-mismatch".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-retry-manager-implementation-mismatch".equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractLacksRecoveryLeaseRepository_reportsUnavailable() {
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile("postgres", "postgres", "database", false, "redis", "redis", true),
                RuntimeCapabilityContracts.resolve("production", profile("postgres", "postgres")),
                productionRuntimeComponents(
                        "tech.example.RedisTaskQueue",
                        "tech.example.PostgresTaskDeadLetterQueue",
                        "tech.example.RedisRetryManager"),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(component("grpcTaskStreamBroker", "tech.example.RedisGrpcTaskStreamBroker")),
                List.of(),
                List.of(EventPublisherDiagnostics.available("event-publisher", Map.of(), null, null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-recovery-lease-implementation-missing"
                        .equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractUsesDurablePublishersAndOutbox_reportsReady() {
        RuntimeProfile profile = profile(
                "postgres",
                "postgres",
                "database",
                false,
                "redis",
                "redis",
                "kafka",
                "postgres",
                true);
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile,
                RuntimeCapabilityContracts.resolve("production", profile),
                productionRuntimeComponents(
                        "tech.example.RedisTaskQueue",
                        "tech.example.PostgresTaskDeadLetterQueue",
                        "tech.example.RedisRetryManager"),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(component("grpcTaskStreamBroker", "tech.example.RedisGrpcTaskStreamBroker")),
                List.of(component("recoveryLeaseRepository", "tech.example.PostgresWorkflowRecoveryLeaseRepository")),
                List.of(component("wakeupOutbox", "tech.example.PostgresWorkflowRunWakeupOutbox")),
                List.of(EventPublisherDiagnostics.available(
                        "tech.example.KafkaEventPublisher",
                        Map.of(),
                        null,
                        null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.READY, health.status());
        assertTrue(health.ready());
        assertTrue(health.issues().isEmpty());
    }

    @Test
    void classify_whenProductionContractUsesDefaultEventPublisher_reportsUnavailable() {
        RuntimeProfile profile = profile(
                "postgres",
                "postgres",
                "database",
                false,
                "redis",
                "redis",
                "kafka",
                "postgres",
                true);
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile,
                RuntimeCapabilityContracts.resolve("production", profile),
                productionRuntimeComponents(
                        "tech.example.RedisTaskQueue",
                        "tech.example.PostgresTaskDeadLetterQueue",
                        "tech.example.RedisRetryManager"),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(component("grpcTaskStreamBroker", "tech.example.RedisGrpcTaskStreamBroker")),
                List.of(component("recoveryLeaseRepository", "tech.example.PostgresWorkflowRecoveryLeaseRepository")),
                List.of(component("wakeupOutbox", "tech.example.PostgresWorkflowRunWakeupOutbox")),
                List.of(EventPublisherDiagnostics.available(
                        "tech.example.DefaultEventPublisher",
                        Map.of(),
                        null,
                        null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-event-publisher-implementation-mismatch"
                        .equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    @Test
    void classify_whenProductionContractUsesMemoryWakeupOutbox_reportsUnavailable() {
        RuntimeProfile profile = profile(
                "postgres",
                "postgres",
                "database",
                false,
                "redis",
                "redis",
                "kafka",
                "postgres",
                true);
        RuntimeCapabilityHealth health = RuntimeCapabilityHealthClassifier.classify(
                profile,
                RuntimeCapabilityContracts.resolve("production", profile),
                productionRuntimeComponents(
                        "tech.example.RedisTaskQueue",
                        "tech.example.PostgresTaskDeadLetterQueue",
                        "tech.example.RedisRetryManager"),
                List.of(component("agentContextStore", "tech.example.PostgresAgentContextStore")),
                List.of(component("grpcTaskStreamBroker", "tech.example.RedisGrpcTaskStreamBroker")),
                List.of(component("recoveryLeaseRepository", "tech.example.PostgresWorkflowRecoveryLeaseRepository")),
                List.of(component("wakeupOutbox", "tech.example.InMemoryWorkflowRunWakeupOutbox")),
                List.of(EventPublisherDiagnostics.available(
                        "tech.example.KafkaEventPublisher",
                        Map.of(),
                        null,
                        null)),
                List.of(wakeupPublisher(Map.of(), null)),
                new RuntimeExecutionContext().status(),
                Instant.EPOCH);

        assertEquals(Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "runtime-contract-wakeup-outbox-implementation-mismatch"
                        .equals(issue.code())
                        && issue.severity() == Severity.ERROR));
    }

    private static RuntimeComponent component(String role, boolean available) {
        return new RuntimeComponent(role, available, available ? role + "-impl" : null);
    }

    private static RuntimeComponent component(String role, String implementation) {
        return new RuntimeComponent(role, implementation != null, implementation);
    }

    private static RuntimeProfile profile(String workflowPersistenceStore, String agentContextStore) {
        return profile(workflowPersistenceStore, agentContextStore, "memory", false, "memory");
    }

    private static RuntimeProfile profile(
            String workflowPersistenceStore,
            String agentContextStore,
            String registryPersistenceType,
            boolean grpcTaskStreamDefaultEnabled,
            String grpcTaskStreamBroker) {
        return profile(
                workflowPersistenceStore,
                agentContextStore,
                registryPersistenceType,
                grpcTaskStreamDefaultEnabled,
                grpcTaskStreamBroker,
                "local",
                "local",
                "auto",
                false);
    }

    private static RuntimeProfile profile(
            String workflowPersistenceStore,
            String agentContextStore,
            String registryPersistenceType,
            boolean grpcTaskStreamDefaultEnabled,
            String grpcTaskStreamBroker,
            String schedulerMode,
            boolean recoveryDistributedLeaseEnabled) {
        return profile(
                workflowPersistenceStore,
                agentContextStore,
                registryPersistenceType,
                grpcTaskStreamDefaultEnabled,
                grpcTaskStreamBroker,
                schedulerMode,
                "local",
                "auto",
                recoveryDistributedLeaseEnabled);
    }

    private static RuntimeProfile profile(
            String workflowPersistenceStore,
            String agentContextStore,
            String registryPersistenceType,
            boolean grpcTaskStreamDefaultEnabled,
            String grpcTaskStreamBroker,
            String schedulerMode,
            String eventPublisherFamily,
            String wakeupOutboxStore,
            boolean recoveryDistributedLeaseEnabled) {
        return new RuntimeProfile(
                "test",
                workflowPersistenceStore,
                agentContextStore,
                registryPersistenceType,
                "round-robin",
                false,
                grpcTaskStreamDefaultEnabled,
                grpcTaskStreamBroker,
                schedulerMode,
                eventPublisherFamily,
                wakeupOutboxStore,
                recoveryDistributedLeaseEnabled);
    }

    private static List<RuntimeComponent> postgresWorkflowComponents() {
        return List.of(
                component(
                        "workflowDefinitionRepository",
                        "tech.example.PostgresWorkflowDefinitionRepository"),
                component(
                        "workflowRunRepository",
                        "tech.example.PostgresWorkflowRunRepository"),
                component(
                        "executionHistoryRepository",
                        "tech.example.PostgresExecutionHistoryRepository"));
    }

    private static List<RuntimeComponent> productionRuntimeComponents(
            String taskQueueImplementation,
            String taskDeadLetterQueueImplementation,
            String retryManagerImplementation) {
        List<RuntimeComponent> workflowComponents = postgresWorkflowComponents();
        return List.of(
                workflowComponents.get(0),
                workflowComponents.get(1),
                workflowComponents.get(2),
                component("taskQueue", taskQueueImplementation),
                component("taskDeadLetterQueue", taskDeadLetterQueueImplementation),
                component("retryManager", retryManagerImplementation));
    }

    private static RuntimeCapabilityContract localContract() {
        return RuntimeCapabilityContracts.resolve("local", profile("default", "default"));
    }

    private static WorkflowRunWakeupPublisherDiagnostics wakeupPublisher(
            Map<String, Long> counters,
            String lastFailure) {
        return WorkflowRunWakeupPublisherDiagnostics.available(
                "wakeup-publisher",
                false,
                0,
                64,
                8,
                counters,
                lastFailure,
                lastFailure != null ? Instant.EPOCH : null);
    }
}
