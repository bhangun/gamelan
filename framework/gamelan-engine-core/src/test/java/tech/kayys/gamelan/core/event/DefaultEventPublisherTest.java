package tech.kayys.gamelan.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.inject.Instance;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.signal.SignalContext;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.plugin.impl.DefaultExtensionRegistry;

class DefaultEventPublisherTest {

    @Test
    void publishBatchAppendsTypedEventsToPersistence() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.persistence = persistence;

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeStartedEvent event = new NodeStartedEvent("event-1", runId, NodeId.of("node-1"), 1, Instant.EPOCH);

        publisher.publish(List.of(event)).await().indefinitely();

        assertEquals(List.of("NodeStarted"), persistence.eventTypes);
        assertEquals(List.of(event), persistence.payloads);
        EventPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertEquals(1, diagnostics.counter("batchEventsPublished"));
        assertEquals(0, diagnostics.counter("batchEventsSkipped"));
        assertEquals(0, diagnostics.counter("persistenceAppendFailures"));
    }

    @Test
    void publishBatchSkipsNullAndMissingRunEventsInDiagnostics() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.persistence = persistence;

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeStartedEvent valid = new NodeStartedEvent("event-1", runId, NodeId.of("node-1"), 1, Instant.EPOCH);
        NodeStartedEvent missingRun = new NodeStartedEvent("event-2", null, NodeId.of("node-2"), 1, Instant.EPOCH);
        List<ExecutionEvent> events = new ArrayList<>();
        events.add(null);
        events.add(missingRun);
        events.add(valid);

        publisher.publish(events).await().indefinitely();

        assertEquals(List.of("NodeStarted"), persistence.eventTypes);
        assertEquals(List.of(valid), persistence.payloads);
        EventPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertEquals(1, diagnostics.counter("batchEventsPublished"));
        assertEquals(2, diagnostics.counter("batchEventsSkipped"));
    }

    @Test
    void publishRetryAppendsRetryEventNotifiesInterceptorsAndWakesRunBus() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        RecordingInterceptor interceptor = new RecordingInterceptor();
        registry.registerInterceptor(interceptor);
        EventBus eventBus = mock(EventBus.class);

        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.persistence = persistence;
        publisher.extensionRegistry = registry;
        publisher.eventBus = eventBus;

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeId nodeId = NodeId.of("node-1");

        publisher.publishRetry(runId, nodeId).await().indefinitely();

        assertEquals(List.of(DefaultEventPublisher.RETRY_EVENT_TYPE), persistence.eventTypes);
        GenericExecutionEvent event = assertInstanceOf(GenericExecutionEvent.class, persistence.payloads.get(0));
        assertEquals(runId, event.runId());
        assertEquals(DefaultEventPublisher.RETRY_EVENT_TYPE, event.eventType());
        assertEquals(Map.of("nodeId", nodeId.value()), event.metadata());
        assertEquals(List.of(DefaultEventPublisher.RETRY_EVENT_TYPE), interceptor.systemEventTypes);
        ArgumentCaptor<JsonObject> payload = ArgumentCaptor.forClass(JsonObject.class);
        verify(eventBus).publish(eq(DefaultEventPublisher.RUN_UPDATED_ADDRESS), payload.capture());
        assertEquals(runId.value(), payload.getValue().getString("runId"));
        assertNull(payload.getValue().getString("tenantId"));
        assertEquals("retry-requested", payload.getValue().getString("reason"));
    }

    @Test
    void publishRetryWithTenantPreservesTenantInEventMetadataAndWakeup() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        EventBus eventBus = mock(EventBus.class);

        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.persistence = persistence;
        publisher.eventBus = eventBus;

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        TenantId tenantId = TenantId.of("tenant-1");
        NodeId nodeId = NodeId.of("node-1");

        publisher.publishRetry(runId, tenantId, nodeId, 2).await().indefinitely();

        GenericExecutionEvent event = assertInstanceOf(GenericExecutionEvent.class, persistence.payloads.get(0));
        assertEquals(Map.of("nodeId", nodeId.value(), "tenantId", tenantId.value(), "attempt", 2), event.metadata());
        ArgumentCaptor<JsonObject> payload = ArgumentCaptor.forClass(JsonObject.class);
        verify(eventBus).publish(eq(DefaultEventPublisher.RUN_UPDATED_ADDRESS), payload.capture());
        assertEquals(runId.value(), payload.getValue().getString("runId"));
        assertEquals(tenantId.value(), payload.getValue().getString("tenantId"));
        assertEquals("retry-requested", payload.getValue().getString("reason"));
    }

    @Test
    void publishRetryFallsBackToEventBusWhenConfiguredWakeupPublisherFails() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        EventBus eventBus = mock(EventBus.class);
        WorkflowRunWakeupPublisher failingPublisher = event -> Uni.createFrom()
                .failure(new IllegalStateException("outbox down"));

        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.persistence = persistence;
        publisher.eventBus = eventBus;
        publisher.wakeupPublishers = instance(failingPublisher);

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        TenantId tenantId = TenantId.of("tenant-1");
        NodeId nodeId = NodeId.of("node-1");

        publisher.publishRetry(runId, tenantId, nodeId, 2).await().indefinitely();

        assertEquals(List.of(DefaultEventPublisher.RETRY_EVENT_TYPE), persistence.eventTypes);
        ArgumentCaptor<JsonObject> payload = ArgumentCaptor.forClass(JsonObject.class);
        verify(eventBus).publish(eq(DefaultEventPublisher.RUN_UPDATED_ADDRESS), payload.capture());
        assertEquals(runId.value(), payload.getValue().getString("runId"));
        assertEquals(tenantId.value(), payload.getValue().getString("tenantId"));
        assertEquals("retry-requested", payload.getValue().getString("reason"));

        EventPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertTrue(diagnostics.available());
        assertEquals(1, diagnostics.counter("retryEventsPublished"));
        assertEquals(0, diagnostics.counter("wakeupPublisherPublished"));
        assertEquals(1, diagnostics.counter("wakeupEventBusPublished"));
        assertEquals(1, diagnostics.counter("wakeupFallbackPublished"));
        assertEquals(1, diagnostics.counter("wakeupFailures"));
        assertTrue(diagnostics.lastFailure().contains("outbox down"));
    }

    @Test
    void publishContinuesAfterFailingInterceptor() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        RecordingInterceptor recording = new RecordingInterceptor();
        registry.registerInterceptor(new FailingInterceptor());
        registry.registerInterceptor(recording);

        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.persistence = persistence;
        publisher.extensionRegistry = registry;

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        publisher.publish("CustomEvent", Map.of("ok", true), workflowContext(runId));

        assertEquals(List.of("CustomEvent"), persistence.eventTypes);
        assertEquals(List.of("CustomEvent"), recording.workflowEventTypes);
        EventPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertEquals(1, diagnostics.counter("workflowEventsPublished"));
        assertEquals(1, diagnostics.counter("interceptorFailures"));
        assertTrue(diagnostics.lastFailure().contains("subscriber failed"));
    }

    @Test
    void publishSystemTracksDiagnosticsAndContinuesAfterFailingInterceptor() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        RecordingInterceptor recording = new RecordingInterceptor();
        registry.registerInterceptor(new FailingInterceptor());
        registry.registerInterceptor(recording);

        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.extensionRegistry = registry;

        publisher.publishSystem("SystemEvent", Map.of("ok", true));

        assertEquals(List.of("SystemEvent"), recording.systemEventTypes);
        EventPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertEquals(1, diagnostics.counter("systemEventsPublished"));
        assertEquals(1, diagnostics.counter("interceptorFailures"));
        assertTrue(diagnostics.lastFailure().contains("subscriber failed"));
    }

    @Test
    void publishRecordsPersistenceAppendFailuresBeforeRethrowing() {
        FailingPersistenceProvider persistence = new FailingPersistenceProvider();

        DefaultEventPublisher publisher = new DefaultEventPublisher();
        publisher.persistence = persistence;

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> publisher.publish("CustomEvent", Map.of("ok", true), workflowContext(WorkflowRunId.of("run-1"))));

        assertEquals("disk full", error.getMessage());
        EventPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertEquals(0, diagnostics.counter("workflowEventsPublished"));
        assertEquals(1, diagnostics.counter("persistenceAppendFailures"));
        assertTrue(diagnostics.lastFailure().contains("disk full"));
    }

    private static WorkflowContext workflowContext(WorkflowRunId runId) {
        return new WorkflowContext() {
            @Override
            public WorkflowRunId runId() {
                return runId;
            }

            @Override
            public WorkflowDefinitionId definitionId() {
                return WorkflowDefinitionId.of("wf-1");
            }

            @Override
            public TenantId tenantId() {
                return TenantId.of("tenant-1");
            }

            @Override
            public RunStatus status() {
                return RunStatus.RUNNING;
            }

            @Override
            public Instant startedAt() {
                return Instant.EPOCH;
            }

            @Override
            public Instant updatedAt() {
                return Instant.EPOCH;
            }

            @Override
            public Map<String, Object> variables() {
                return Map.of();
            }

            @Override
            public Map<NodeId, NodeResult> completedNodes() {
                return Map.of();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Instance<WorkflowRunWakeupPublisher> instance(WorkflowRunWakeupPublisher publisher) {
        Instance<WorkflowRunWakeupPublisher> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(publisher);
        return instance;
    }

    static class RecordingPersistenceProvider implements PersistenceProvider {
        final List<String> eventTypes = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();

        @Override
        public void saveWorkflow(WorkflowContext workflow) {
        }

        @Override
        public Optional<WorkflowContext> loadWorkflow(WorkflowRunId runId) {
            return Optional.empty();
        }

        @Override
        public void appendEvent(WorkflowRunId runId, String eventType, Object payload) {
            eventTypes.add(eventType);
            payloads.add(payload);
        }

        @Override
        public void saveNodeResult(WorkflowRunId runId, NodeId nodeId, NodeResult result) {
        }

        @Override
        public void saveSignal(WorkflowRunId runId, SignalContext signal) {
        }

        @Override
        public void updateContextVariable(WorkflowRunId runId, String key, Object value) {
        }

        @Override
        public void updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
        }
    }

    static class FailingPersistenceProvider extends RecordingPersistenceProvider {
        @Override
        public void appendEvent(WorkflowRunId runId, String eventType, Object payload) {
            throw new IllegalStateException("disk full");
        }
    }

    static class RecordingInterceptor implements WorkflowInterceptor {
        final List<String> workflowEventTypes = new ArrayList<>();
        final List<String> systemEventTypes = new ArrayList<>();

        @Override
        public void onEvent(String eventType, Object payload, WorkflowContext ctx) {
            workflowEventTypes.add(eventType);
        }

        @Override
        public void onSystemEvent(String eventType, Object payload) {
            systemEventTypes.add(eventType);
        }
    }

    static class FailingInterceptor implements WorkflowInterceptor {
        @Override
        public void onEvent(String eventType, Object payload, WorkflowContext ctx) {
            throw new IllegalStateException("subscriber failed");
        }

        @Override
        public void onSystemEvent(String eventType, Object payload) {
            throw new IllegalStateException("subscriber failed");
        }
    }
}
