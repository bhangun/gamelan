package tech.kayys.gamelan.runtime.resource;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.gamelan.dispatcher.GrpcTaskStreamBroker;
import tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator;
import tech.kayys.gamelan.engine.agent.context.AgentContextStore;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.EventStore;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisherDiagnostics;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLeaseRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.registry.ExecutorRegistryService;
import tech.kayys.gamelan.runtime.ExecutorAdapterRegistry;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.ExecutorAdapterCapability;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeCapabilities;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeComponent;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeProbeConfig;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeProfile;
import tech.kayys.gamelan.scheduler.RetryManager;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskWorker;

/**
 * Discovers active runtime components and evaluates them against profile contracts.
 */
@ApplicationScoped
public class RuntimeCapabilityInspector {

    @Inject
    TaskQueue taskQueue;

    @Inject
    TaskDeadLetterQueue deadLetterQueue;

    @Inject
    TaskWorker taskWorker;

    @Inject
    RuntimeExecutionContext runtimeExecutionContext;

    @Inject
    WorkflowDefinitionRepository workflowDefinitionRepository;

    @Inject
    WorkflowRunRepository workflowRunRepository;

    @Inject
    ExecutionHistoryRepository executionHistoryRepository;

    @Inject
    ExecutorRegistryService executorRegistryService;

    @Inject
    ExecutorAdapterRegistry executorAdapterRegistry;

    @Inject
    TaskDispatcherAggregator taskDispatcherAggregator;

    @Inject
    RetryManager retryManager;

    @Inject
    Instance<EventStore> eventStores;

    @Inject
    Instance<EventPublisher> eventPublishers;

    @Inject
    Instance<WorkflowRunWakeupPublisher> wakeupPublishers;

    @Inject
    Instance<WorkflowRunWakeupOutbox> wakeupOutboxes;

    @Inject
    Instance<AgentContextStore> agentContextStores;

    @Inject
    Instance<GrpcTaskStreamBroker> grpcTaskStreamBrokers;

    @Inject
    Instance<WorkflowRecoveryLeaseRepository> recoveryLeaseRepositories;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "default")
    String quarkusProfile;

    @ConfigProperty(name = "gamelan.workflow.persistence.store", defaultValue = "default")
    String workflowPersistenceStore;

    @ConfigProperty(name = "gamelan.agent.context.store", defaultValue = "default")
    String agentContextStore;

    @ConfigProperty(name = "gamelan.registry.persistence.type", defaultValue = "memory")
    String registryPersistenceType;

    @ConfigProperty(name = "gamelan.registry.selection.strategy", defaultValue = "round-robin")
    String registrySelectionStrategy;

    @ConfigProperty(name = "gamelan.registry.selection.prefer-local", defaultValue = "false")
    Boolean registryPreferLocal;

    @ConfigProperty(name = "gamelan.grpc.task-stream.default-enabled", defaultValue = "false")
    Boolean grpcTaskStreamDefaultEnabled;

    @ConfigProperty(name = "gamelan.grpc.task-stream.broker", defaultValue = "memory")
    String grpcTaskStreamBroker;

    @ConfigProperty(name = "gamelan.scheduler.mode", defaultValue = "local")
    String schedulerMode;

    @ConfigProperty(name = "gamelan.event.publisher.family", defaultValue = "local")
    String eventPublisherFamily;

    @ConfigProperty(name = "gamelan.workflow.wakeup.outbox.store", defaultValue = "auto")
    String wakeupOutboxStore;

    @ConfigProperty(name = "gamelan.recovery.distributed-lease.enabled", defaultValue = "false")
    Boolean recoveryDistributedLeaseEnabled;

    @ConfigProperty(name = "gamelan.runtime.capabilities.contract", defaultValue = "auto")
    String runtimeCapabilityContract;

    @ConfigProperty(name = "gamelan.task-runtime.status.component-timeout", defaultValue = "2s")
    Duration statusComponentTimeout;

    @ConfigProperty(name = "gamelan.task-runtime.status.cache-ttl", defaultValue = "0s")
    Duration statusCacheTtl;

    public RuntimeCapabilities capabilities() {
        Instant observedAt = Instant.now();
        RuntimeProfile profile = profile();
        RuntimeCapabilityContract contract = contract(profile);
        List<RuntimeComponent> components = requiredComponents();
        List<RuntimeComponent> eventStores = optionalComponents("eventStore", this.eventStores);
        List<RuntimeComponent> agentContextStores = optionalComponents("agentContextStore", this.agentContextStores);
        List<RuntimeComponent> grpcTaskStreamBrokers =
                optionalComponents("grpcTaskStreamBroker", this.grpcTaskStreamBrokers);
        List<RuntimeComponent> recoveryLeaseRepositories =
                optionalComponents("recoveryLeaseRepository", this.recoveryLeaseRepositories);
        List<RuntimeComponent> wakeupOutboxes = optionalComponents("wakeupOutbox", this.wakeupOutboxes);
        List<ExecutorAdapterCapability> executorAdapters = executorAdapters();
        List<TaskDispatcherAggregator.TaskDispatcherCapability> taskDispatchers = taskDispatchers();
        List<EventPublisherDiagnostics> eventPublisherDiagnostics = eventPublisherDiagnostics();
        List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublisherDiagnostics = wakeupPublisherDiagnostics();
        RuntimeExecutionContext.RuntimeExecutionStatus executionContext = runtimeExecutionStatus();
        RuntimeCapabilityHealth health = classifyHealth(
                profile,
                contract,
                components,
                agentContextStores,
                grpcTaskStreamBrokers,
                recoveryLeaseRepositories,
                wakeupOutboxes,
                eventPublisherDiagnostics,
                wakeupPublisherDiagnostics,
                executionContext,
                observedAt);

        return new RuntimeCapabilities(
                profile,
                contract,
                components,
                eventStores,
                agentContextStores,
                grpcTaskStreamBrokers,
                recoveryLeaseRepositories,
                wakeupOutboxes,
                executorAdapters,
                taskDispatchers,
                eventPublisherDiagnostics,
                wakeupPublisherDiagnostics,
                executionContext,
                health,
                probeConfig(),
                observedAt);
    }

    public RuntimeCapabilityHealth health() {
        return health(Instant.now());
    }

    RuntimeCapabilityHealth health(Instant observedAt) {
        RuntimeProfile profile = profile();
        return classifyHealth(
                profile,
                contract(profile),
                requiredComponents(),
                optionalComponents("agentContextStore", this.agentContextStores),
                optionalComponents("grpcTaskStreamBroker", this.grpcTaskStreamBrokers),
                optionalComponents("recoveryLeaseRepository", this.recoveryLeaseRepositories),
                optionalComponents("wakeupOutbox", this.wakeupOutboxes),
                eventPublisherDiagnostics(),
                wakeupPublisherDiagnostics(),
                runtimeExecutionStatus(),
                observedAt);
    }

    private RuntimeCapabilityHealth classifyHealth(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> agentContextStores,
            List<RuntimeComponent> grpcTaskStreamBrokers,
            List<RuntimeComponent> recoveryLeaseRepositories,
            List<RuntimeComponent> wakeupOutboxes,
            List<EventPublisherDiagnostics> eventPublisherDiagnostics,
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublisherDiagnostics,
            RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
            Instant observedAt) {
        return RuntimeCapabilityHealthClassifier.classify(
                profile,
                contract,
                components,
                agentContextStores,
                grpcTaskStreamBrokers,
                recoveryLeaseRepositories,
                wakeupOutboxes,
                eventPublisherDiagnostics,
                wakeupPublisherDiagnostics,
                executionContext,
                observedAt);
    }

    private List<RuntimeComponent> requiredComponents() {
        return List.of(
                component("taskQueue", taskQueue),
                component("taskDeadLetterQueue", deadLetterQueue),
                component("taskWorker", taskWorker),
                component("runtimeExecutionContext", runtimeExecutionContext),
                component("workflowDefinitionRepository", workflowDefinitionRepository),
                component("workflowRunRepository", workflowRunRepository),
                component("executionHistoryRepository", executionHistoryRepository),
                component("executorRegistryService", executorRegistryService),
                component("executorAdapterRegistry", executorAdapterRegistry),
                component("taskDispatcherAggregator", taskDispatcherAggregator),
                component("retryManager", retryManager));
    }

    private RuntimeProfile profile() {
        return new RuntimeProfile(
                valueOrDefault(quarkusProfile, "default"),
                valueOrDefault(workflowPersistenceStore, "default"),
                valueOrDefault(agentContextStore, "default"),
                valueOrDefault(registryPersistenceType, "memory"),
                valueOrDefault(registrySelectionStrategy, "round-robin"),
                Boolean.TRUE.equals(registryPreferLocal),
                Boolean.TRUE.equals(grpcTaskStreamDefaultEnabled),
                valueOrDefault(grpcTaskStreamBroker, "memory"),
                valueOrDefault(schedulerMode, "local"),
                valueOrDefault(eventPublisherFamily, "local"),
                valueOrDefault(wakeupOutboxStore, "auto"),
                Boolean.TRUE.equals(recoveryDistributedLeaseEnabled));
    }

    private RuntimeCapabilityContract contract(RuntimeProfile profile) {
        return RuntimeCapabilityContracts.resolve(runtimeCapabilityContract, profile);
    }

    private RuntimeProbeConfig probeConfig() {
        return new RuntimeProbeConfig(
                durationMillis(statusComponentTimeout),
                durationMillis(statusCacheTtl));
    }

    private RuntimeExecutionContext.RuntimeExecutionStatus runtimeExecutionStatus() {
        return runtimeExecutionContext != null ? runtimeExecutionContext.status() : null;
    }

    private List<ExecutorAdapterCapability> executorAdapters() {
        if (executorAdapterRegistry == null) {
            return List.of();
        }
        return executorAdapterRegistry.getAllAdapters().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ExecutorAdapterCapability(
                        entry.getKey(),
                        implementationName(entry.getValue())))
                .toList();
    }

    private List<TaskDispatcherAggregator.TaskDispatcherCapability> taskDispatchers() {
        if (taskDispatcherAggregator == null) {
            return List.of();
        }
        return taskDispatcherAggregator.capabilities().stream()
                .sorted(Comparator
                        .comparingInt(TaskDispatcherAggregator.TaskDispatcherCapability::priority)
                        .reversed()
                        .thenComparing(TaskDispatcherAggregator.TaskDispatcherCapability::implementation))
                .toList();
    }

    private List<EventPublisherDiagnostics> eventPublisherDiagnostics() {
        if (eventPublishers == null || eventPublishers.isUnsatisfied()) {
            return List.of();
        }
        return eventPublishers.stream()
                .map(RuntimeCapabilityInspector::eventPublisherDiagnostics)
                .sorted(Comparator.comparing(EventPublisherDiagnostics::implementation))
                .toList();
    }

    private static EventPublisherDiagnostics eventPublisherDiagnostics(EventPublisher publisher) {
        if (publisher == null) {
            return EventPublisherDiagnostics.unavailable("<null>", "event-publisher-null");
        }
        try {
            EventPublisherDiagnostics diagnostics = publisher.diagnostics();
            return diagnostics != null
                    ? diagnostics
                    : EventPublisherDiagnostics.unavailable(
                            implementationName(publisher),
                            "event-publisher-diagnostics-null");
        } catch (RuntimeException error) {
            return EventPublisherDiagnostics.unavailable(implementationName(publisher), errorSummary(error));
        }
    }

    private List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublisherDiagnostics() {
        if (wakeupPublishers == null || wakeupPublishers.isUnsatisfied()) {
            return List.of();
        }
        return wakeupPublishers.stream()
                .map(RuntimeCapabilityInspector::wakeupPublisherDiagnostics)
                .sorted(Comparator.comparing(WorkflowRunWakeupPublisherDiagnostics::implementation))
                .toList();
    }

    private static WorkflowRunWakeupPublisherDiagnostics wakeupPublisherDiagnostics(
            WorkflowRunWakeupPublisher publisher) {
        if (publisher == null) {
            return WorkflowRunWakeupPublisherDiagnostics.unavailable("<null>", "wakeup-publisher-null");
        }
        try {
            WorkflowRunWakeupPublisherDiagnostics diagnostics = publisher.diagnostics();
            return diagnostics != null
                    ? diagnostics
                    : WorkflowRunWakeupPublisherDiagnostics.unavailable(
                            implementationName(publisher),
                            "wakeup-publisher-diagnostics-null");
        } catch (RuntimeException error) {
            return WorkflowRunWakeupPublisherDiagnostics.unavailable(implementationName(publisher), errorSummary(error));
        }
    }

    private static RuntimeComponent component(String role, Object component) {
        return new RuntimeComponent(
                role,
                component != null,
                implementationName(component));
    }

    private static <T> List<RuntimeComponent> optionalComponents(String role, Instance<T> components) {
        if (components == null || components.isUnsatisfied()) {
            return List.of();
        }
        return components.stream()
                .map(component -> component(role, component))
                .sorted(Comparator.comparing(RuntimeComponent::implementation))
                .toList();
    }

    private static String implementationName(Object component) {
        return component != null ? component.getClass().getName() : null;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static long durationMillis(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return 0;
        }
        return duration.toMillis();
    }

    private static String errorSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }
}
