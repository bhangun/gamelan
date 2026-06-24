package tech.kayys.gamelan.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.dispatcher.GrpcTaskStreamBroker;
import tech.kayys.gamelan.dispatcher.TaskDispatcher;
import tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisherDiagnostics;
import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.impl.InMemoryExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.runtime.ExecutorAdapter;
import tech.kayys.gamelan.runtime.ExecutorAdapterRegistry;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityHealth;
import tech.kayys.gamelan.runtime.repository.InMemoryWorkflowDefinitionRepository;
import tech.kayys.gamelan.runtime.repository.InMemoryWorkflowRunRepository;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityInspector;
import tech.kayys.gamelan.scheduler.RetryManager;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskWorker;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.registry.ExecutorRegistryService;

class RuntimeCapabilitiesResourceUnitTest {

    @Test
    void capabilitiesReportActiveImplementationsAndProfileConfig() throws ReflectiveOperationException {
        RuntimeCapabilityInspector inspector = new RuntimeCapabilityInspector();
        RuntimeCapabilitiesResource resource = resource(inspector);
        RecordingTaskQueue taskQueue = new RecordingTaskQueue();
        RecordingDeadLetterQueue deadLetterQueue = new RecordingDeadLetterQueue();
        TaskDispatcherAggregator dispatcherAggregator = new TaskDispatcherAggregator();
        dispatcherAggregator.registerDispatcher(new RecordingTaskDispatcher());
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();
        RecordingWakeupOutbox wakeupOutbox = new RecordingWakeupOutbox();
        RecordingGrpcTaskStreamBroker grpcTaskStreamBroker = new RecordingGrpcTaskStreamBroker();

        set(inspector, "taskQueue", taskQueue);
        set(inspector, "deadLetterQueue", deadLetterQueue);
        set(inspector, "taskWorker", new TaskWorker());
        set(inspector, "runtimeExecutionContext", new RuntimeExecutionContext());
        set(inspector, "workflowDefinitionRepository", new InMemoryWorkflowDefinitionRepository());
        set(inspector, "workflowRunRepository", new InMemoryWorkflowRunRepository());
        set(inspector, "executionHistoryRepository", new InMemoryExecutionHistoryRepository());
        set(inspector, "executorRegistryService", mock(ExecutorRegistryService.class));
        set(inspector, "executorAdapterRegistry", new ExecutorAdapterRegistry(Stream.of(new RecordingExecutorAdapter())));
        set(inspector, "taskDispatcherAggregator", dispatcherAggregator);
        set(inspector, "retryManager", new RecordingRetryManager());
        set(inspector, "eventPublishers", instance(eventPublisher));
        set(inspector, "wakeupPublishers", instance(wakeupPublisher));
        set(inspector, "wakeupOutboxes", instance(wakeupOutbox));
        set(inspector, "grpcTaskStreamBrokers", instance(grpcTaskStreamBroker));
        set(inspector, "quarkusProfile", "test");
        set(inspector, "workflowPersistenceStore", "memory");
        set(inspector, "agentContextStore", "default");
        set(inspector, "registryPersistenceType", "memory");
        set(inspector, "registrySelectionStrategy", "least-loaded");
        set(inspector, "registryPreferLocal", true);
        set(inspector, "grpcTaskStreamDefaultEnabled", true);
        set(inspector, "grpcTaskStreamBroker", "memory");
        set(inspector, "schedulerMode", "local");
        set(inspector, "eventPublisherFamily", "local");
        set(inspector, "wakeupOutboxStore", "memory");
        set(inspector, "recoveryDistributedLeaseEnabled", false);
        set(inspector, "statusComponentTimeout", Duration.ofSeconds(3));
        set(inspector, "statusCacheTtl", Duration.ofSeconds(1));

        RuntimeCapabilitiesResource.RuntimeCapabilities capabilities = resource.capabilities();

        assertEquals("test", capabilities.profile().quarkusProfile());
        assertEquals("local", capabilities.contract().name());
        assertEquals("auto", capabilities.contract().selectedBy());
        assertTrue(capabilities.contract().enabled());
        assertEquals("memory", capabilities.profile().workflowPersistenceStore());
        assertEquals("default", capabilities.profile().agentContextStore());
        assertEquals("memory", capabilities.profile().registryPersistenceType());
        assertEquals("least-loaded", capabilities.profile().registrySelectionStrategy());
        assertTrue(capabilities.profile().registryPreferLocal());
        assertTrue(capabilities.profile().grpcTaskStreamDefaultEnabled());
        assertEquals("memory", capabilities.profile().grpcTaskStreamBroker());
        assertEquals("local", capabilities.profile().schedulerMode());
        assertEquals("local", capabilities.profile().eventPublisherFamily());
        assertEquals("memory", capabilities.profile().wakeupOutboxStore());
        assertFalse(capabilities.profile().recoveryDistributedLeaseEnabled());
        assertEquals(3000, capabilities.probes().statusComponentTimeoutMillis());
        assertEquals(1000, capabilities.probes().statusCacheTtlMillis());
        assertComponent(capabilities, "taskQueue", RecordingTaskQueue.class);
        assertComponent(capabilities, "taskDeadLetterQueue", RecordingDeadLetterQueue.class);
        assertComponent(capabilities, "taskWorker", TaskWorker.class);
        assertComponent(capabilities, "runtimeExecutionContext", RuntimeExecutionContext.class);
        assertComponent(capabilities, "workflowDefinitionRepository", InMemoryWorkflowDefinitionRepository.class);
        assertComponent(capabilities, "workflowRunRepository", InMemoryWorkflowRunRepository.class);
        assertComponent(capabilities, "executionHistoryRepository", InMemoryExecutionHistoryRepository.class);
        assertComponent(capabilities, "retryManager", RecordingRetryManager.class);
        assertNotNull(capabilities.executionContext());
        assertEquals("gamelan-runtime-exec-", capabilities.executionContext().threadNamePrefix());
        assertEquals(5000, capabilities.executionContext().shutdownGracePeriodMillis());
        assertTrue(capabilities.executorAdapters().stream()
                .anyMatch(adapter -> "recording".equals(adapter.executorType())
                        && adapter.implementation().equals(RecordingExecutorAdapter.class.getName())));
        assertTrue(capabilities.taskDispatchers().stream()
                .anyMatch(dispatcher -> dispatcher.implementation().equals(RecordingTaskDispatcher.class.getName())
                        && dispatcher.priority() == 42));
        assertTrue(capabilities.grpcTaskStreamBrokers().stream()
                .anyMatch(broker -> "grpcTaskStreamBroker".equals(broker.role())
                        && broker.available()
                        && broker.implementation().equals(RecordingGrpcTaskStreamBroker.class.getName())));
        assertEquals(1, capabilities.eventPublishers().size());
        EventPublisherDiagnostics publisherDiagnostics = capabilities.eventPublishers().get(0);
        assertEquals(RecordingEventPublisher.class.getName(), publisherDiagnostics.implementation());
        assertTrue(publisherDiagnostics.available());
        assertEquals(4, publisherDiagnostics.counter("publishedEvents"));
        assertEquals(1, capabilities.wakeupPublishers().size());
        WorkflowRunWakeupPublisherDiagnostics wakeupDiagnostics = capabilities.wakeupPublishers().get(0);
        assertEquals(RecordingWakeupPublisher.class.getName(), wakeupDiagnostics.implementation());
        assertTrue(wakeupDiagnostics.available());
        assertEquals(2, wakeupDiagnostics.counter("deliveredWakeups"));
        assertEquals(3, wakeupDiagnostics.drainBatchSize());
        assertEquals(1, capabilities.wakeupOutboxes().size());
        assertEquals(RecordingWakeupOutbox.class.getName(), capabilities.wakeupOutboxes().get(0).implementation());
        assertEquals(RuntimeCapabilityHealth.Status.READY, capabilities.health().status());
        assertTrue(capabilities.health().ready());
        assertTrue(capabilities.health().issues().isEmpty());
        assertFalse(capabilities.cache().enabled());
        assertFalse(capabilities.cache().hit());
        RuntimeCapabilityHealth health = resource.health();
        assertEquals(RuntimeCapabilityHealth.Status.READY, health.status());
        assertTrue(health.ready());
        assertTrue(health.issues().isEmpty());
        Response readinessResponse = resource.readiness();
        assertEquals(Response.Status.OK.getStatusCode(), readinessResponse.getStatus());
        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                (RuntimeCapabilitiesResource.RuntimeCapabilityReadiness) readinessResponse.getEntity();
        assertTrue(readiness.ready());
        assertEquals(RuntimeCapabilityHealth.Status.READY, readiness.status());
        assertTrue(readiness.issueCodes().isEmpty());
        assertTrue(readiness.issues().isEmpty());
        assertEquals(0, readiness.totalIssueCount());
        assertEquals(20, readiness.issueDetailLimit());
        assertFalse(readiness.issueDetailsTruncated());
        assertFalse(readiness.cache().enabled());
        assertFalse(readiness.cache().hit());
        assertTrue(readiness.policy().acceptDegraded());
        assertTrue(capabilities.eventStores().isEmpty());
        assertTrue(capabilities.agentContextStores().isEmpty());
        assertTrue(capabilities.recoveryLeaseRepositories().isEmpty());
        assertNotNull(capabilities.observedAt());
    }

    @Test
    void readinessWhenRequiredComponentsAreMissingReturnsServiceUnavailable() throws ReflectiveOperationException {
        RuntimeCapabilitiesResource resource = resource(new RuntimeCapabilityInspector());

        RuntimeCapabilityHealth health = resource.health();
        Response readinessResponse = resource.readiness();

        assertEquals(RuntimeCapabilityHealth.Status.UNAVAILABLE, health.status());
        assertFalse(health.ready());
        assertTrue(health.issues().stream()
                .anyMatch(issue -> "component-unavailable".equals(issue.code())
                        && "workflowRunRepository".equals(issue.component())));
        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), readinessResponse.getStatus());
        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                (RuntimeCapabilitiesResource.RuntimeCapabilityReadiness) readinessResponse.getEntity();
        assertFalse(readiness.ready());
        assertEquals(RuntimeCapabilityHealth.Status.UNAVAILABLE, readiness.status());
        assertEquals("runtime-capability-unavailable", readiness.rejectionReason());
        assertTrue(readiness.issueCodes().contains("component-unavailable"));
        assertEquals(health.issues().size(), readiness.totalIssueCount());
        assertEquals(20, readiness.issueDetailLimit());
        assertFalse(readiness.issueDetailsTruncated());
        assertTrue(readiness.issues().stream()
                .anyMatch(issue -> "component-unavailable".equals(issue.code())
                        && "workflowRunRepository".equals(issue.component())
                        && issue.message().contains("Required runtime component is not available")));
    }

    @Test
    void readinessWhenDegradedAndStrictPolicyReturnsServiceUnavailable() throws ReflectiveOperationException {
        RuntimeCapabilityInspector inspector = new RuntimeCapabilityInspector();
        RuntimeCapabilitiesResource resource = resource(inspector);
        setReadyRuntimeComponents(inspector);
        set(inspector, "eventPublishers", instance(new DegradedEventPublisher()));
        set(inspector, "wakeupPublishers", instance(new RecordingWakeupPublisher()));
        set(resource, "runtimeCapabilitiesReadinessAcceptDegraded", false);

        RuntimeCapabilityHealth health = resource.health();
        Response readinessResponse = resource.readiness();

        assertEquals(RuntimeCapabilityHealth.Status.DEGRADED, health.status());
        assertTrue(health.ready());
        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), readinessResponse.getStatus());
        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                (RuntimeCapabilitiesResource.RuntimeCapabilityReadiness) readinessResponse.getEntity();
        assertFalse(readiness.ready());
        assertEquals(RuntimeCapabilityHealth.Status.DEGRADED, readiness.status());
        assertFalse(readiness.policy().acceptDegraded());
        assertEquals("runtime-capability-health-not-accepted", readiness.rejectionReason());
        assertTrue(readiness.issueCodes().contains("event-publisher-failures"));
        assertEquals(health.issues().size(), readiness.totalIssueCount());
        assertEquals(20, readiness.issueDetailLimit());
        assertFalse(readiness.issueDetailsTruncated());
        assertTrue(readiness.issues().stream()
                .anyMatch(issue -> "event-publisher-failures".equals(issue.code())
                        && "eventPublisher".equals(issue.component())
                        && issue.message().contains("Workflow event persistence append failures")));
    }

    @Test
    void readinessBoundsIssueDetailsWithoutDroppingStableIssueCodes() {
        List<RuntimeCapabilityHealth.Issue> issues = IntStream.range(0, 25)
                .mapToObj(index -> new RuntimeCapabilityHealth.Issue(
                        "issue-" + index,
                        RuntimeCapabilityHealth.Severity.WARN,
                        "component-" + index,
                        null,
                        "diagnostic " + index))
                .toList();
        RuntimeCapabilityHealth health = RuntimeCapabilityHealth.fromIssues(issues, Instant.EPOCH);

        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                RuntimeCapabilitiesResource.RuntimeCapabilityReadiness.from(
                        health,
                        new RuntimeCapabilitiesResource.RuntimeCapabilityReadinessPolicy(true));

        assertTrue(readiness.ready());
        assertEquals(RuntimeCapabilityHealth.Status.DEGRADED, readiness.status());
        assertEquals(25, readiness.issueCodes().size());
        assertTrue(readiness.issueCodes().contains("issue-24"));
        assertEquals(20, readiness.issueDetailLimit());
        assertEquals(20, readiness.issues().size());
        assertEquals("issue-0", readiness.issues().get(0).code());
        assertEquals("issue-19", readiness.issues().get(19).code());
        assertEquals(25, readiness.totalIssueCount());
        assertTrue(readiness.issueDetailsTruncated());
    }

    @Test
    void readinessUsesConfiguredIssueDetailLimit() {
        List<RuntimeCapabilityHealth.Issue> issues = IntStream.range(0, 6)
                .mapToObj(index -> new RuntimeCapabilityHealth.Issue(
                        "issue-" + index,
                        RuntimeCapabilityHealth.Severity.WARN,
                        "component-" + index,
                        null,
                        "diagnostic " + index))
                .toList();
        RuntimeCapabilityHealth health = RuntimeCapabilityHealth.fromIssues(issues, Instant.EPOCH);

        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                RuntimeCapabilitiesResource.RuntimeCapabilityReadiness.from(
                        health,
                        new RuntimeCapabilitiesResource.RuntimeCapabilityReadinessPolicy(true),
                        2);

        assertTrue(readiness.ready());
        assertEquals(6, readiness.issueCodes().size());
        assertTrue(readiness.issueCodes().contains("issue-5"));
        assertEquals(2, readiness.issueDetailLimit());
        assertEquals(2, readiness.issues().size());
        assertEquals("issue-0", readiness.issues().get(0).code());
        assertEquals("issue-1", readiness.issues().get(1).code());
        assertEquals(6, readiness.totalIssueCount());
        assertTrue(readiness.issueDetailsTruncated());
    }

    @Test
    void readinessAllowsCodeOnlyIssuePayloads() {
        List<RuntimeCapabilityHealth.Issue> issues = IntStream.range(0, 3)
                .mapToObj(index -> new RuntimeCapabilityHealth.Issue(
                        "issue-" + index,
                        RuntimeCapabilityHealth.Severity.WARN,
                        "component-" + index,
                        null,
                        "diagnostic " + index))
                .toList();
        RuntimeCapabilityHealth health = RuntimeCapabilityHealth.fromIssues(issues, Instant.EPOCH);

        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                RuntimeCapabilitiesResource.RuntimeCapabilityReadiness.from(
                        health,
                        new RuntimeCapabilitiesResource.RuntimeCapabilityReadinessPolicy(true),
                        0);

        assertTrue(readiness.ready());
        assertEquals(3, readiness.issueCodes().size());
        assertEquals(0, readiness.issueDetailLimit());
        assertTrue(readiness.issues().isEmpty());
        assertEquals(3, readiness.totalIssueCount());
        assertTrue(readiness.issueDetailsTruncated());
    }

    @Test
    void capabilitiesHealthAndReadinessReuseCachedSnapshotWithinConfiguredTtl()
            throws ReflectiveOperationException {
        CountingRuntimeCapabilityInspector inspector = new CountingRuntimeCapabilityInspector();
        RuntimeCapabilitiesResource resource = resource(inspector);
        set(resource, "runtimeCapabilitiesCacheTtl", Duration.ofMinutes(1));

        RuntimeCapabilitiesResource.RuntimeCapabilities first = resource.capabilities();
        RuntimeCapabilitiesResource.RuntimeCapabilities second = resource.capabilities();
        RuntimeCapabilityHealth health = resource.health();
        Response readinessResponse = resource.readiness();
        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                (RuntimeCapabilitiesResource.RuntimeCapabilityReadiness) readinessResponse.getEntity();

        assertEquals(1, inspector.capabilitiesCalls);
        assertTrue(first.cache().enabled());
        assertFalse(first.cache().hit());
        assertEquals(Duration.ofMinutes(1).toMillis(), first.cache().ttlMillis());
        assertEquals(0, first.cache().ageMillis());
        assertNotNull(first.cache().expiresAt());
        assertTrue(second.cache().enabled());
        assertTrue(second.cache().hit());
        assertEquals(first.observedAt(), second.observedAt());
        assertEquals(first.health().observedAt(), health.observedAt());
        assertEquals(Response.Status.OK.getStatusCode(), readinessResponse.getStatus());
        assertTrue(readiness.cache().enabled());
        assertTrue(readiness.cache().hit());
        assertEquals(first.observedAt(), readiness.observedAt());
    }

    @Test
    void capabilitiesCacheIsDisabledByZeroTtl() throws ReflectiveOperationException {
        CountingRuntimeCapabilityInspector inspector = new CountingRuntimeCapabilityInspector();
        RuntimeCapabilitiesResource resource = resource(inspector);
        set(resource, "runtimeCapabilitiesCacheTtl", Duration.ZERO);

        RuntimeCapabilitiesResource.RuntimeCapabilities first = resource.capabilities();
        RuntimeCapabilityHealth health = resource.health();
        Response readinessResponse = resource.readiness();
        RuntimeCapabilitiesResource.RuntimeCapabilityReadiness readiness =
                (RuntimeCapabilitiesResource.RuntimeCapabilityReadiness) readinessResponse.getEntity();

        assertEquals(3, inspector.capabilitiesCalls);
        assertFalse(first.cache().enabled());
        assertFalse(first.cache().hit());
        assertFalse(readiness.cache().enabled());
        assertFalse(readiness.cache().hit());
        assertEquals(RuntimeCapabilityHealth.Status.READY, health.status());
        assertEquals(Response.Status.OK.getStatusCode(), readinessResponse.getStatus());
    }

    private static void assertComponent(
            RuntimeCapabilitiesResource.RuntimeCapabilities capabilities,
            String role,
            Class<?> implementation) {
        assertTrue(capabilities.components().stream()
                .anyMatch(component -> role.equals(component.role())
                        && component.available()
                        && component.implementation().equals(implementation.getName())),
                "Expected component role " + role + " with implementation " + implementation.getName());
    }

    private static void set(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static RuntimeCapabilitiesResource resource(RuntimeCapabilityInspector inspector)
            throws ReflectiveOperationException {
        RuntimeCapabilitiesResource resource = new RuntimeCapabilitiesResource();
        set(resource, "capabilityInspector", inspector);
        return resource;
    }

    private static void setReadyRuntimeComponents(RuntimeCapabilityInspector inspector)
            throws ReflectiveOperationException {
        TaskDispatcherAggregator dispatcherAggregator = new TaskDispatcherAggregator();
        dispatcherAggregator.registerDispatcher(new RecordingTaskDispatcher());

        set(inspector, "taskQueue", new RecordingTaskQueue());
        set(inspector, "deadLetterQueue", new RecordingDeadLetterQueue());
        set(inspector, "taskWorker", new TaskWorker());
        set(inspector, "runtimeExecutionContext", new RuntimeExecutionContext());
        set(inspector, "workflowDefinitionRepository", new InMemoryWorkflowDefinitionRepository());
        set(inspector, "workflowRunRepository", new InMemoryWorkflowRunRepository());
        set(inspector, "executionHistoryRepository", new InMemoryExecutionHistoryRepository());
        set(inspector, "executorRegistryService", mock(ExecutorRegistryService.class));
        set(inspector, "executorAdapterRegistry", new ExecutorAdapterRegistry(Stream.of(new RecordingExecutorAdapter())));
        set(inspector, "taskDispatcherAggregator", dispatcherAggregator);
        set(inspector, "retryManager", new RecordingRetryManager());
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T... components) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(components == null || components.length == 0);
        when(instance.stream()).thenAnswer(ignored -> components == null ? Stream.empty() : Stream.of(components));
        return instance;
    }

    private static final class RecordingExecutorAdapter implements ExecutorAdapter {

        @Override
        public boolean supports(String executorType) {
            return "recording".equals(executorType);
        }

        @Override
        public ExecutorClient adapt(ExecutorClient client) {
            return client;
        }

        @Override
        public CompletionStage<NodeResult> execute(NodeContext nodeContext, Map<String, Object> variables) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String getExecutorType() {
            return "recording";
        }
    }

    private static final class CountingRuntimeCapabilityInspector extends RuntimeCapabilityInspector {

        private int capabilitiesCalls;

        @Override
        public RuntimeCapabilitiesResource.RuntimeCapabilities capabilities() {
            capabilitiesCalls++;
            RuntimeCapabilitiesResource.RuntimeProfile profile =
                    new RuntimeCapabilitiesResource.RuntimeProfile(
                            "test",
                            "memory",
                            "default",
                            "memory",
                            "round-robin",
                            true,
                            false,
                            "memory",
                            "local",
                            "local",
                            "memory",
                            false);
            Instant observedAt = Instant.parse("2026-06-09T00:00:00Z").plusSeconds(capabilitiesCalls);
            return new RuntimeCapabilitiesResource.RuntimeCapabilities(
                    profile,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    RuntimeCapabilityHealth.fromIssues(List.of(), observedAt),
                    new RuntimeCapabilitiesResource.RuntimeProbeConfig(0, 0),
                    observedAt);
        }
    }

    private static final class RecordingTaskDispatcher implements TaskDispatcher {

        @Override
        public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public int getPriority() {
            return 42;
        }
    }

    private static final class RecordingRetryManager implements RetryManager {

        @Override
        public Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay) {
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingGrpcTaskStreamBroker implements GrpcTaskStreamBroker {

        @Override
        public Uni<Void> assign(String executorId, NodeExecutionTask task) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Multi<StreamedTask> stream(String executorId, int maxConcurrent) {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> acknowledge(String taskId) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> complete(String taskId) {
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingEventPublisher implements EventPublisher {

        @Override
        public void publish(String eventType, Object payload, WorkflowContext workflowContext) {
        }

        @Override
        public void publishSystem(String eventType, Object payload) {
        }

        @Override
        public Uni<Void> publish(List<ExecutionEvent> events) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> publishRetry(WorkflowRunId runId, NodeId nodeId) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public EventPublisherDiagnostics diagnostics() {
            return EventPublisherDiagnostics.available(
                    getClass().getName(),
                    Map.of("publishedEvents", 4L),
                    null,
                    null);
        }
    }

    private static final class DegradedEventPublisher implements EventPublisher {

        @Override
        public void publish(String eventType, Object payload, WorkflowContext workflowContext) {
        }

        @Override
        public void publishSystem(String eventType, Object payload) {
        }

        @Override
        public Uni<Void> publish(List<ExecutionEvent> events) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> publishRetry(WorkflowRunId runId, NodeId nodeId) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public EventPublisherDiagnostics diagnostics() {
            return EventPublisherDiagnostics.available(
                    getClass().getName(),
                    Map.of("persistenceAppendFailures", 1L),
                    "append failed",
                    null);
        }
    }

    private static final class RecordingWakeupPublisher implements WorkflowRunWakeupPublisher {

        @Override
        public Uni<Void> publish(WorkflowRunUpdateEvent event) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public WorkflowRunWakeupPublisherDiagnostics diagnostics() {
            return WorkflowRunWakeupPublisherDiagnostics.available(
                    getClass().getName(),
                    false,
                    1,
                    3,
                    2,
                    Map.of("deliveredWakeups", 2L),
                    null,
                    null);
        }
    }

    private static final class RecordingWakeupOutbox implements WorkflowRunWakeupOutbox {

        @Override
        public Uni<WorkflowRunWakeupIntent> enqueue(WorkflowRunUpdateEvent event) {
            return Uni.createFrom().item(WorkflowRunWakeupIntent.pending(event, Instant.now()));
        }

        @Override
        public Uni<List<WorkflowRunWakeupIntent>> pending(int maxItems) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> markFailed(String intentId, Throwable error) {
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingTaskQueue implements TaskQueue {

        @Override
        public Uni<Void> enqueue(NodeExecutionTask task) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Multi<QueuedTask> consume() {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> acknowledge(String messageId) {
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingDeadLetterQueue implements TaskDeadLetterQueue {

        @Override
        public Uni<Void> publish(DeadLetterTask task) {
            return Uni.createFrom().voidItem();
        }
    }
}
