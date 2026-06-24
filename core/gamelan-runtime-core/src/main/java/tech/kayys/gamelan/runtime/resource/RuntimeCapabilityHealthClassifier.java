package tech.kayys.gamelan.runtime.resource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisherDiagnostics;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityHealth.Issue;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityHealth.Severity;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeComponent;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeProfile;

/**
 * Converts discovered runtime capabilities into stable health issues for probes,
 * startup validation, and profile contract enforcement.
 */
final class RuntimeCapabilityHealthClassifier {

    private static final Map<String, String> EVENT_FAILURE_COUNTERS = Map.of(
            "interceptorFailures", "Event publisher interceptor failures have been observed.",
            "persistenceAppendFailures", "Workflow event persistence append failures have been observed.",
            "publishFailures", "Event publisher transport failures have been observed.",
            "wakeupFailures", "Workflow wake-up publication failures have been observed.",
            "wakeupMissingPublisher", "Workflow wake-up events were emitted without a wake-up publisher.");

    private static final Map<String, String> WAKEUP_FAILURE_COUNTERS = Map.of(
            "enqueueFailures", "Workflow wake-up enqueue failures have been observed.",
            "deliveryFailures", "Workflow wake-up delivery failures have been observed.",
            "drainFailures", "Workflow wake-up drain failures have been observed.");

    private RuntimeCapabilityHealthClassifier() {
    }

    static RuntimeCapabilityHealth classify(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> agentContextStores,
            List<EventPublisherDiagnostics> eventPublishers,
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
            RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
            Instant observedAt) {
        return classify(
                profile,
                contract,
                components,
                agentContextStores,
                List.of(),
                List.of(),
                eventPublishers,
                wakeupPublishers,
                executionContext,
                observedAt);
    }

    static RuntimeCapabilityHealth classify(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> agentContextStores,
            List<RuntimeComponent> grpcTaskStreamBrokers,
            List<EventPublisherDiagnostics> eventPublishers,
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
            RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
            Instant observedAt) {
        return classify(
                profile,
                contract,
                components,
                agentContextStores,
                grpcTaskStreamBrokers,
                List.of(),
                eventPublishers,
                wakeupPublishers,
                executionContext,
                observedAt);
    }

    static RuntimeCapabilityHealth classify(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> agentContextStores,
            List<RuntimeComponent> grpcTaskStreamBrokers,
            List<RuntimeComponent> recoveryLeaseRepositories,
            List<EventPublisherDiagnostics> eventPublishers,
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
            RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
            Instant observedAt) {
        return classify(
                profile,
                contract,
                components,
                agentContextStores,
                grpcTaskStreamBrokers,
                recoveryLeaseRepositories,
                List.of(),
                eventPublishers,
                wakeupPublishers,
                executionContext,
                observedAt);
    }

    static RuntimeCapabilityHealth classify(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> agentContextStores,
            List<RuntimeComponent> grpcTaskStreamBrokers,
            List<RuntimeComponent> recoveryLeaseRepositories,
            List<RuntimeComponent> wakeupOutboxes,
            List<EventPublisherDiagnostics> eventPublishers,
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
            RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
            Instant observedAt) {

        List<Issue> issues = new ArrayList<>();
        requiredComponentIssues(components, issues);
        profileConsistencyIssues(profile, components, agentContextStores, issues);
        contractIssues(
                profile,
                contract,
                components,
                agentContextStores,
                grpcTaskStreamBrokers,
                recoveryLeaseRepositories,
                wakeupOutboxes,
                eventPublishers,
                wakeupPublishers,
                issues);
        eventPublisherIssues(eventPublishers, issues);
        wakeupPublisherIssues(wakeupPublishers, issues);
        executionContextIssues(executionContext, issues);
        return RuntimeCapabilityHealth.fromIssues(issues, observedAt);
    }

    private static void requiredComponentIssues(List<RuntimeComponent> components, List<Issue> issues) {
        List<RuntimeComponent> safeComponents = components != null ? components : List.of();
        for (RuntimeComponent component : safeComponents) {
            if (component == null) {
                issues.add(issue(
                        "component-null",
                        Severity.ERROR,
                        "runtime",
                        null,
                        "Runtime capabilities contain a null required component entry."));
                continue;
            }
            if (!component.available()) {
                issues.add(issue(
                        "component-unavailable",
                        Severity.ERROR,
                        component.role(),
                        component.implementation(),
                        "Required runtime component is not available: " + component.role()));
            }
        }
    }

    private static void profileConsistencyIssues(
            RuntimeProfile profile,
            List<RuntimeComponent> components,
            List<RuntimeComponent> agentContextStores,
            List<Issue> issues) {
        if (profile == null) {
            return;
        }
        workflowPersistenceProfileIssues(profile.workflowPersistenceStore(), components, issues);
        agentContextProfileIssues(profile.agentContextStore(), agentContextStores, issues);
    }

    private static void workflowPersistenceProfileIssues(
            String configuredStore,
            List<RuntimeComponent> components,
            List<Issue> issues) {
        String expectedToken = expectedImplementationToken(configuredStore);
        if (expectedToken == null) {
            return;
        }
        for (String role : List.of(
                "workflowDefinitionRepository",
                "workflowRunRepository",
                "executionHistoryRepository")) {
            RuntimeComponent component = componentByRole(components, role);
            if (component == null || !component.available()) {
                continue;
            }
            if (!matchesImplementation(component.implementation(), expectedToken)) {
                issues.add(issue(
                        "workflow-persistence-profile-mismatch",
                        Severity.ERROR,
                        role,
                        component.implementation(),
                        "Configured workflow persistence store is " + configuredStore
                                + " but " + role + " does not use a " + expectedToken + " implementation."));
            }
        }
    }

    private static void agentContextProfileIssues(
            String configuredStore,
            List<RuntimeComponent> agentContextStores,
            List<Issue> issues) {
        String expectedToken = expectedImplementationToken(configuredStore);
        if (expectedToken == null || "inmemory".equals(expectedToken)) {
            return;
        }
        List<RuntimeComponent> stores = agentContextStores != null ? agentContextStores : List.of();
        if (stores.isEmpty()) {
            issues.add(issue(
                    "agent-context-profile-missing",
                    Severity.WARN,
                    "agentContextStore",
                    null,
                    "Configured agent context store is " + configuredStore
                            + " but no AgentContextStore implementation is available."));
            return;
        }
        boolean hasMatchingStore = stores.stream()
                .filter(component -> component != null && component.available())
                .anyMatch(component -> matchesImplementation(component.implementation(), expectedToken));
        if (!hasMatchingStore) {
            issues.add(issue(
                    "agent-context-profile-mismatch",
                    Severity.WARN,
                    "agentContextStore",
                    stores.stream()
                            .filter(component -> component != null && component.implementation() != null)
                            .map(RuntimeComponent::implementation)
                            .findFirst()
                            .orElse(null),
                    "Configured agent context store is " + configuredStore
                            + " but no discovered AgentContextStore uses a " + expectedToken + " implementation."));
        }
    }

    private static void contractIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> agentContextStores,
            List<RuntimeComponent> grpcTaskStreamBrokers,
            List<RuntimeComponent> recoveryLeaseRepositories,
            List<RuntimeComponent> wakeupOutboxes,
            List<EventPublisherDiagnostics> eventPublishers,
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
            List<Issue> issues) {
        if (profile == null || contract == null || !contract.enabled()) {
            return;
        }
        allowedStoreIssue(
                "runtime-contract-workflow-persistence-violation",
                "workflowPersistenceStore",
                profile.workflowPersistenceStore(),
                contract.allowedWorkflowPersistenceStores(),
                contract.name(),
                issues);
        allowedStoreIssue(
                "runtime-contract-agent-context-violation",
                "agentContextStore",
                profile.agentContextStore(),
                contract.allowedAgentContextStores(),
                contract.name(),
                issues);
        workflowPersistenceImplementationIssues(profile, contract, components, issues);
        agentContextImplementationIssues(profile, contract, agentContextStores, issues);
        taskRuntimeImplementationIssues(profile, contract, components, recoveryLeaseRepositories, issues);
        allowedStoreIssue(
                "runtime-contract-event-publisher-family-violation",
                "eventPublisherFamily",
                profile.eventPublisherFamily(),
                contract.allowedEventPublisherFamilies(),
                contract.name(),
                issues);
        allowedStoreIssue(
                "runtime-contract-wakeup-outbox-store-violation",
                "wakeupOutboxStore",
                effectiveWakeupOutboxStore(profile),
                contract.allowedWakeupOutboxStores(),
                contract.name(),
                issues);
        eventPublisherContractIssues(profile, contract, eventPublishers, issues);
        wakeupOutboxContractIssues(profile, contract, wakeupOutboxes, issues);
        disallowedStoreIssue(
                "runtime-contract-registry-persistence-violation",
                "registryPersistenceType",
                profile.registryPersistenceType(),
                contract.disallowedRegistryPersistenceTypes(),
                contract.name(),
                issues);
        if (grpcBrokerContractApplies(profile, contract)) {
            allowedStoreIssue(
                    "runtime-contract-grpc-task-stream-broker-violation",
                    "grpcTaskStreamBroker",
                    profile.grpcTaskStreamBroker(),
                    contract.allowedGrpcTaskStreamBrokers(),
                    contract.name(),
                issues);
            grpcTaskStreamBrokerImplementationIssues(profile, contract, grpcTaskStreamBrokers, issues);
        }
        if (contract.wakeupPublisherRequired() && !hasAvailableWakeupPublisher(wakeupPublishers)) {
            issues.add(issue(
                    "runtime-contract-wakeup-publisher-required",
                    Severity.ERROR,
                    "wakeupPublisher",
                    null,
                    "Runtime capability contract " + contract.name()
                            + " requires at least one available workflow wake-up publisher."));
        }
    }

    private static void eventPublisherContractIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<EventPublisherDiagnostics> eventPublishers,
            List<Issue> issues) {
        List<EventPublisherDiagnostics> availablePublishers = availableEventPublishers(eventPublishers);
        if (contract.eventPublisherRequired() && availablePublishers.isEmpty()) {
            issues.add(issue(
                    "runtime-contract-event-publisher-required",
                    Severity.ERROR,
                    "eventPublisher",
                    null,
                    "Runtime capability contract " + contract.name()
                            + " requires at least one available event publisher."));
            return;
        }

        String family = normalizeStore(profile.eventPublisherFamily());
        if (!configuredStoreAllowed(family, contract.allowedEventPublisherFamilies())) {
            return;
        }
        if (availablePublishers.isEmpty()) {
            return;
        }

        if ("custom".equals(family)) {
            boolean hasCustomPublisher = availablePublishers.stream()
                    .anyMatch(publisher -> !matchesImplementation(publisher.implementation(), "default"));
            if (!hasCustomPublisher) {
                issues.add(issue(
                        "runtime-contract-event-publisher-implementation-mismatch",
                        Severity.ERROR,
                        "eventPublisher",
                        firstEventPublisherImplementation(availablePublishers),
                        "Runtime capability contract " + contract.name()
                                + " requires a non-default event publisher for eventPublisherFamily=custom."));
            }
            return;
        }

        String expectedToken = expectedEventPublisherToken(family);
        if (expectedToken == null) {
            return;
        }
        boolean hasMatchingPublisher = availablePublishers.stream()
                .anyMatch(publisher -> matchesImplementation(publisher.implementation(), expectedToken));
        if (!hasMatchingPublisher) {
            issues.add(issue(
                    "runtime-contract-event-publisher-implementation-mismatch",
                    Severity.ERROR,
                    "eventPublisher",
                    firstEventPublisherImplementation(availablePublishers),
                    "Runtime capability contract " + contract.name()
                            + " requires an event publisher using a " + expectedToken
                            + " implementation."));
        }
    }

    private static void wakeupOutboxContractIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> wakeupOutboxes,
            List<Issue> issues) {
        String outboxStore = effectiveWakeupOutboxStore(profile);
        if (!configuredStoreAllowed(outboxStore, contract.allowedWakeupOutboxStores())) {
            return;
        }

        List<RuntimeComponent> availableOutboxes = availableComponents(wakeupOutboxes);
        if (availableOutboxes.isEmpty()) {
            issues.add(issue(
                    "runtime-contract-wakeup-outbox-implementation-missing",
                    Severity.ERROR,
                    "wakeupOutbox",
                    null,
                    "Runtime capability contract " + contract.name()
                            + " requires an available WorkflowRunWakeupOutbox implementation for wakeupOutboxStore="
                            + valueOrDefault(outboxStore) + "."));
            return;
        }

        String expectedToken = expectedImplementationToken(outboxStore);
        if (expectedToken == null) {
            return;
        }
        boolean hasMatchingOutbox = availableOutboxes.stream()
                .anyMatch(component -> matchesImplementation(component.implementation(), expectedToken));
        if (!hasMatchingOutbox) {
            issues.add(issue(
                    "runtime-contract-wakeup-outbox-implementation-mismatch",
                    Severity.ERROR,
                    "wakeupOutbox",
                    firstComponentImplementation(availableOutboxes),
                    "Runtime capability contract " + contract.name()
                            + " requires a workflow wake-up outbox using a " + expectedToken
                            + " implementation."));
        }
    }

    private static void grpcTaskStreamBrokerImplementationIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> grpcTaskStreamBrokers,
            List<Issue> issues) {
        String normalizedBroker = normalizeStore(profile.grpcTaskStreamBroker());
        if (!configuredStoreAllowed(normalizedBroker, contract.allowedGrpcTaskStreamBrokers())) {
            return;
        }
        List<RuntimeComponent> brokers = grpcTaskStreamBrokers != null ? grpcTaskStreamBrokers : List.of();
        List<RuntimeComponent> availableBrokers = brokers.stream()
                .filter(component -> component != null && component.available())
                .toList();
        if (availableBrokers.isEmpty()) {
            issues.add(issue(
                    "runtime-contract-grpc-task-stream-broker-implementation-missing",
                    Severity.ERROR,
                    "grpcTaskStreamBroker",
                    null,
                    "Runtime capability contract " + contract.name()
                            + " requires an available gRPC task-stream broker for grpcTaskStreamBroker="
                            + valueOrDefault(profile.grpcTaskStreamBroker()) + "."));
            return;
        }
        String expectedToken = expectedImplementationToken(normalizedBroker);
        if (expectedToken == null) {
            return;
        }
        boolean hasMatchingBroker = availableBrokers.stream()
                .anyMatch(component -> matchesImplementation(component.implementation(), expectedToken));
        if (!hasMatchingBroker) {
            issues.add(issue(
                    "runtime-contract-grpc-task-stream-broker-implementation-mismatch",
                    Severity.ERROR,
                    "grpcTaskStreamBroker",
                    availableBrokers.stream()
                            .map(RuntimeComponent::implementation)
                            .filter(implementation -> implementation != null && !implementation.isBlank())
                            .findFirst()
                            .orElse(null),
                    "Runtime capability contract " + contract.name()
                            + " requires a gRPC task-stream broker using a " + expectedToken
                            + " implementation."));
        }
    }

    private static void taskRuntimeImplementationIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> recoveryLeaseRepositories,
            List<Issue> issues) {
        allowedStoreIssue(
                "runtime-contract-scheduler-mode-violation",
                "schedulerMode",
                profile.schedulerMode(),
                contract.allowedSchedulerModes(),
                contract.name(),
                issues);

        String schedulerMode = normalizeStore(profile.schedulerMode());
        if (configuredStoreAllowed(schedulerMode, contract.allowedSchedulerModes())) {
            taskRuntimeComponentImplementationIssue(
                    "runtime-contract-task-queue-implementation",
                    "taskQueue",
                    expectedTaskQueueToken(profile),
                    contract.name(),
                    components,
                    issues);
            taskRuntimeComponentImplementationIssue(
                    "runtime-contract-retry-manager-implementation",
                    "retryManager",
                    expectedRetryManagerToken(schedulerMode),
                    contract.name(),
                    components,
                    issues);
        }

        String workflowStore = normalizeStore(profile.workflowPersistenceStore());
        if (configuredStoreAllowed(workflowStore, contract.allowedWorkflowPersistenceStores())) {
            taskRuntimeComponentImplementationIssue(
                    "runtime-contract-task-dead-letter-implementation",
                    "taskDeadLetterQueue",
                    expectedImplementationToken(workflowStore),
                    contract.name(),
                    components,
                    issues);
        }

        if (contract.recoveryLeaseRequired() || profile.recoveryDistributedLeaseEnabled()) {
            recoveryLeaseRepositoryImplementationIssues(
                    profile,
                    contract,
                    recoveryLeaseRepositories,
                    issues);
        }
    }

    private static void taskRuntimeComponentImplementationIssue(
            String codePrefix,
            String role,
            String expectedToken,
            String contractName,
            List<RuntimeComponent> components,
            List<Issue> issues) {
        if (expectedToken == null) {
            return;
        }
        RuntimeComponent component = componentByRole(components, role);
        if (component == null || !component.available()) {
            issues.add(issue(
                    codePrefix + "-missing",
                    Severity.ERROR,
                    role,
                    component != null ? component.implementation() : null,
                    "Runtime capability contract " + contractName
                            + " requires an available " + expectedToken
                            + " implementation for " + role + "."));
            return;
        }
        if (!matchesImplementation(component.implementation(), expectedToken)) {
            issues.add(issue(
                    codePrefix + "-mismatch",
                    Severity.ERROR,
                    role,
                    component.implementation(),
                    "Runtime capability contract " + contractName
                            + " requires " + role + " to use a " + expectedToken
                            + " implementation."));
        }
    }

    private static void recoveryLeaseRepositoryImplementationIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> recoveryLeaseRepositories,
            List<Issue> issues) {
        String expectedToken = expectedImplementationToken(normalizeStore(profile.workflowPersistenceStore()));
        List<RuntimeComponent> repositories =
                recoveryLeaseRepositories != null ? recoveryLeaseRepositories : List.of();
        List<RuntimeComponent> availableRepositories = repositories.stream()
                .filter(component -> component != null && component.available())
                .toList();
        if (availableRepositories.isEmpty()) {
            issues.add(issue(
                    "runtime-contract-recovery-lease-implementation-missing",
                    Severity.ERROR,
                    "recoveryLeaseRepository",
                    null,
                    "Runtime capability contract " + contract.name()
                            + " requires an available WorkflowRecoveryLeaseRepository implementation."));
            return;
        }
        if (expectedToken == null) {
            return;
        }
        boolean hasMatchingRepository = availableRepositories.stream()
                .anyMatch(component -> matchesImplementation(component.implementation(), expectedToken));
        if (!hasMatchingRepository) {
            issues.add(issue(
                    "runtime-contract-recovery-lease-implementation-mismatch",
                    Severity.ERROR,
                    "recoveryLeaseRepository",
                    availableRepositories.stream()
                            .map(RuntimeComponent::implementation)
                            .filter(implementation -> implementation != null && !implementation.isBlank())
                            .findFirst()
                            .orElse(null),
                    "Runtime capability contract " + contract.name()
                            + " requires a recovery lease repository using a " + expectedToken
                            + " implementation."));
        }
    }

    private static void workflowPersistenceImplementationIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<Issue> issues) {
        String normalizedStore = normalizeStore(profile.workflowPersistenceStore());
        if (!configuredStoreAllowed(normalizedStore, contract.allowedWorkflowPersistenceStores())) {
            return;
        }
        String expectedToken = expectedImplementationToken(normalizedStore);
        if (expectedToken == null) {
            return;
        }
        for (String role : List.of(
                "workflowDefinitionRepository",
                "workflowRunRepository",
                "executionHistoryRepository")) {
            RuntimeComponent component = componentByRole(components, role);
            if (component == null || !component.available()) {
                issues.add(issue(
                        "runtime-contract-workflow-persistence-implementation-missing",
                        Severity.ERROR,
                        role,
                        component != null ? component.implementation() : null,
                        "Runtime capability contract " + contract.name()
                                + " requires an available " + expectedToken
                                + " implementation for " + role + "."));
                continue;
            }
            if (!matchesImplementation(component.implementation(), expectedToken)) {
                issues.add(issue(
                        "runtime-contract-workflow-persistence-implementation-mismatch",
                        Severity.ERROR,
                        role,
                        component.implementation(),
                        "Runtime capability contract " + contract.name()
                                + " requires " + role + " to use a " + expectedToken
                                + " implementation."));
            }
        }
    }

    private static void agentContextImplementationIssues(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> agentContextStores,
            List<Issue> issues) {
        String normalizedStore = normalizeStore(profile.agentContextStore());
        if (!configuredStoreAllowed(normalizedStore, contract.allowedAgentContextStores())) {
            return;
        }
        String expectedToken = expectedImplementationToken(normalizedStore);
        if ("inmemory".equals(expectedToken)) {
            return;
        }
        List<RuntimeComponent> stores = agentContextStores != null ? agentContextStores : List.of();
        List<RuntimeComponent> availableStores = stores.stream()
                .filter(component -> component != null && component.available())
                .toList();
        if (availableStores.isEmpty()) {
            issues.add(issue(
                    "runtime-contract-agent-context-implementation-missing",
                    Severity.ERROR,
                    "agentContextStore",
                    null,
                    "Runtime capability contract " + contract.name()
                            + " requires an available AgentContextStore implementation for agentContextStore="
                            + valueOrDefault(profile.agentContextStore()) + "."));
            return;
        }
        if (expectedToken == null) {
            return;
        }
        boolean hasMatchingStore = availableStores.stream()
                .anyMatch(component -> matchesImplementation(component.implementation(), expectedToken));
        if (!hasMatchingStore) {
            issues.add(issue(
                    "runtime-contract-agent-context-implementation-mismatch",
                    Severity.ERROR,
                    "agentContextStore",
                    availableStores.stream()
                            .map(RuntimeComponent::implementation)
                            .filter(implementation -> implementation != null && !implementation.isBlank())
                            .findFirst()
                            .orElse(null),
                    "Runtime capability contract " + contract.name()
                            + " requires an AgentContextStore using a " + expectedToken
                            + " implementation."));
        }
    }

    private static void allowedStoreIssue(
            String code,
            String component,
            String configuredStore,
            List<String> allowedStores,
            String contractName,
            List<Issue> issues) {
        if (allowedStores == null || allowedStores.isEmpty()) {
            return;
        }
        String normalizedStore = normalizeStore(configuredStore);
        if (!allowedStores.contains(normalizedStore)) {
            issues.add(issue(
                    code,
                    Severity.ERROR,
                    component,
                    null,
                    "Runtime capability contract " + contractName + " requires " + component
                            + " to be one of " + allowedStores + " but configured value is "
                            + valueOrDefault(configuredStore) + "."));
        }
    }

    private static void disallowedStoreIssue(
            String code,
            String component,
            String configuredStore,
            List<String> disallowedStores,
            String contractName,
            List<Issue> issues) {
        if (disallowedStores == null || disallowedStores.isEmpty()) {
            return;
        }
        String normalizedStore = normalizeStore(configuredStore);
        if (disallowedStores.contains(normalizedStore)) {
            issues.add(issue(
                    code,
                    Severity.ERROR,
                    component,
                    null,
                    "Runtime capability contract " + contractName + " disallows " + component
                            + "=" + valueOrDefault(configuredStore) + "."));
        }
    }

    private static boolean configuredStoreAllowed(String normalizedStore, List<String> allowedStores) {
        return normalizedStore != null
                && allowedStores != null
                && !allowedStores.isEmpty()
                && allowedStores.contains(normalizedStore);
    }

    private static boolean grpcBrokerContractApplies(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract) {
        return profile.grpcTaskStreamDefaultEnabled()
                || configuredStoreAllowed(
                        normalizeStore(profile.grpcTaskStreamBroker()),
                        contract.allowedGrpcTaskStreamBrokers());
    }

    private static boolean hasAvailableEventPublisher(List<EventPublisherDiagnostics> eventPublishers) {
        return !availableEventPublishers(eventPublishers).isEmpty();
    }

    private static boolean hasAvailableWakeupPublisher(
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers) {
        List<WorkflowRunWakeupPublisherDiagnostics> safePublishers =
                wakeupPublishers != null ? wakeupPublishers : List.of();
        return safePublishers.stream()
                .anyMatch(publisher -> publisher != null && publisher.available());
    }

    private static List<EventPublisherDiagnostics> availableEventPublishers(
            List<EventPublisherDiagnostics> eventPublishers) {
        List<EventPublisherDiagnostics> safePublishers = eventPublishers != null ? eventPublishers : List.of();
        return safePublishers.stream()
                .filter(publisher -> publisher != null && publisher.available())
                .toList();
    }

    private static List<RuntimeComponent> availableComponents(List<RuntimeComponent> components) {
        List<RuntimeComponent> safeComponents = components != null ? components : List.of();
        return safeComponents.stream()
                .filter(component -> component != null && component.available())
                .toList();
    }

    private static String firstEventPublisherImplementation(List<EventPublisherDiagnostics> eventPublishers) {
        return eventPublishers.stream()
                .map(EventPublisherDiagnostics::implementation)
                .filter(implementation -> implementation != null && !implementation.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String firstComponentImplementation(List<RuntimeComponent> components) {
        return components.stream()
                .map(RuntimeComponent::implementation)
                .filter(implementation -> implementation != null && !implementation.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static void eventPublisherIssues(
            List<EventPublisherDiagnostics> eventPublishers,
            List<Issue> issues) {
        List<EventPublisherDiagnostics> safePublishers = eventPublishers != null ? eventPublishers : List.of();
        if (safePublishers.isEmpty()) {
            issues.add(issue(
                    "event-publisher-missing",
                    Severity.WARN,
                    "eventPublisher",
                    null,
                    "No event publisher diagnostics are available."));
            return;
        }
        for (EventPublisherDiagnostics publisher : safePublishers) {
            if (publisher == null) {
                issues.add(issue(
                        "event-publisher-null",
                        Severity.WARN,
                        "eventPublisher",
                        null,
                        "Event publisher diagnostics contain a null entry."));
                continue;
            }
            if (!publisher.available()) {
                issues.add(issue(
                        "event-publisher-unavailable",
                        Severity.WARN,
                        "eventPublisher",
                        publisher.implementation(),
                        "Event publisher diagnostics are unavailable: " + valueOrDefault(publisher.error())));
            }
            failureCounterIssues(
                    "event-publisher-failures",
                    "eventPublisher",
                    publisher.implementation(),
                    publisher.counters(),
                    EVENT_FAILURE_COUNTERS,
                    issues);
            lastFailureIssue(
                    "event-publisher-last-failure",
                    "eventPublisher",
                    publisher.implementation(),
                    publisher.lastFailure(),
                    issues);
        }
    }

    private static void wakeupPublisherIssues(
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
            List<Issue> issues) {
        List<WorkflowRunWakeupPublisherDiagnostics> safePublishers =
                wakeupPublishers != null ? wakeupPublishers : List.of();
        if (safePublishers.isEmpty()) {
            issues.add(issue(
                    "wakeup-publisher-missing",
                    Severity.WARN,
                    "wakeupPublisher",
                    null,
                    "No workflow wake-up publisher diagnostics are available."));
            return;
        }
        for (WorkflowRunWakeupPublisherDiagnostics publisher : safePublishers) {
            if (publisher == null) {
                issues.add(issue(
                        "wakeup-publisher-null",
                        Severity.WARN,
                        "wakeupPublisher",
                        null,
                        "Workflow wake-up publisher diagnostics contain a null entry."));
                continue;
            }
            if (!publisher.available()) {
                issues.add(issue(
                        "wakeup-publisher-unavailable",
                        Severity.WARN,
                        "wakeupPublisher",
                        publisher.implementation(),
                        "Workflow wake-up publisher diagnostics are unavailable: "
                                + valueOrDefault(publisher.error())));
            }
            if (publisher.deliveryParallelism() == 0) {
                issues.add(issue(
                        "wakeup-publisher-delivery-disabled",
                        Severity.WARN,
                        "wakeupPublisher",
                        publisher.implementation(),
                        "Workflow wake-up delivery parallelism is disabled."));
            }
            failureCounterIssues(
                    "wakeup-publisher-failures",
                    "wakeupPublisher",
                    publisher.implementation(),
                    publisher.counters(),
                    WAKEUP_FAILURE_COUNTERS,
                    issues);
            lastFailureIssue(
                    "wakeup-publisher-last-failure",
                    "wakeupPublisher",
                    publisher.implementation(),
                    publisher.lastFailure(),
                    issues);
        }
    }

    private static void executionContextIssues(
            RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
            List<Issue> issues) {
        if (executionContext == null) {
            issues.add(issue(
                    "execution-context-status-missing",
                    Severity.WARN,
                    "runtimeExecutionContext",
                    null,
                    "Runtime execution context status is not available."));
        }
    }

    private static void failureCounterIssues(
            String code,
            String component,
            String implementation,
            Map<String, Long> counters,
            Map<String, String> failureCounters,
            List<Issue> issues) {
        if (counters == null || counters.isEmpty()) {
            return;
        }
        failureCounters.forEach((counter, message) -> {
            long count = Math.max(0L, counters.getOrDefault(counter, 0L));
            if (count > 0) {
                issues.add(issue(
                        code,
                        Severity.WARN,
                        component,
                        implementation,
                        message + " count=" + count));
            }
        });
    }

    private static void lastFailureIssue(
            String code,
            String component,
            String implementation,
            String lastFailure,
            List<Issue> issues) {
        if (lastFailure == null || lastFailure.isBlank()) {
            return;
        }
        issues.add(issue(
                code,
                Severity.INFO,
                component,
                implementation,
                "Last observed failure: " + lastFailure.trim()));
    }

    private static Issue issue(
            String code,
            Severity severity,
            String component,
            String implementation,
            String message) {
        return new Issue(code, severity, component, implementation, message);
    }

    private static RuntimeComponent componentByRole(List<RuntimeComponent> components, String role) {
        List<RuntimeComponent> safeComponents = components != null ? components : List.of();
        return safeComponents.stream()
                .filter(component -> component != null && role.equals(component.role()))
                .findFirst()
                .orElse(null);
    }

    private static String expectedImplementationToken(String configuredStore) {
        String normalizedStore = normalizeStore(configuredStore);
        if (normalizedStore == null) {
            return null;
        }
        return switch (normalizedStore) {
            case "file" -> "file";
            case "postgres" -> "postgres";
            case "memory" -> "inmemory";
            case "redis" -> "redis";
            default -> null;
        };
    }

    private static String expectedTaskQueueToken(RuntimeProfile profile) {
        String schedulerMode = normalizeStore(profile.schedulerMode());
        if ("redis".equals(schedulerMode)) {
            return "redis";
        }
        String workflowStore = normalizeStore(profile.workflowPersistenceStore());
        String workflowToken = expectedImplementationToken(workflowStore);
        return workflowToken != null ? workflowToken : "inmemory";
    }

    private static String expectedRetryManagerToken(String schedulerMode) {
        return "redis".equals(schedulerMode) ? "redis" : "inmemory";
    }

    private static String expectedEventPublisherToken(String family) {
        String normalizedFamily = normalizeStore(family);
        if (normalizedFamily == null) {
            return null;
        }
        return switch (normalizedFamily) {
            case "kafka" -> "kafka";
            case "local", "default" -> "default";
            default -> null;
        };
    }

    private static String effectiveWakeupOutboxStore(RuntimeProfile profile) {
        String configuredStore = normalizeStore(profile.wakeupOutboxStore());
        if (configuredStore == null || "auto".equals(configuredStore)) {
            return normalizeStore(profile.workflowPersistenceStore());
        }
        return configuredStore;
    }

    private static String normalizeStore(String configuredStore) {
        if (configuredStore == null || configuredStore.isBlank()) {
            return null;
        }
        return switch (configuredStore.trim().toLowerCase()) {
            case "file", "filesystem", "fs" -> "file";
            case "postgres", "postgresql", "pg" -> "postgres";
            case "memory", "in-memory", "inmemory" -> "memory";
            case "redis" -> "redis";
            case "local" -> "local";
            case "default" -> "default";
            case "auto" -> "auto";
            case "kafka" -> "kafka";
            case "custom" -> "custom";
            default -> configuredStore.trim().toLowerCase();
        };
    }

    private static boolean matchesImplementation(String implementation, String expectedToken) {
        return implementation != null
                && expectedToken != null
                && implementation.toLowerCase().contains(expectedToken);
    }

    private static String valueOrDefault(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
