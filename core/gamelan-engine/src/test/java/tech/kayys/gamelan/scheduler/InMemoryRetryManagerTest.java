package tech.kayys.gamelan.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class InMemoryRetryManagerTest {

    @Test
    void processRetryQueue_keepsEntryWhenRetryPublishFailsAndRedeliversLater() {
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        publisher.failuresRemaining = 1;
        InMemoryRetryManager manager = new InMemoryRetryManager();
        manager.eventPublisher = publisher;
        WorkflowRunId runId = WorkflowRunId.of("run:1");
        NodeId nodeId = NodeId.of("node:1");

        manager.scheduleRetry(runId, nodeId, Duration.ZERO).await().indefinitely();
        manager.processRetryQueue();
        manager.processRetryQueue();

        assertEquals(List.of(runId, runId), publisher.runIds);
        assertEquals(List.of(nodeId, nodeId), publisher.nodeIds);
    }

    @Test
    void processRetryQueue_removesEntryAfterRetryPublishSucceeds() {
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        InMemoryRetryManager manager = new InMemoryRetryManager();
        manager.eventPublisher = publisher;
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeId nodeId = NodeId.of("node-1");

        manager.scheduleRetry(runId, nodeId, Duration.ZERO).await().indefinitely();
        manager.processRetryQueue();
        manager.processRetryQueue();

        assertEquals(List.of(runId), publisher.runIds);
        assertEquals(List.of(nodeId), publisher.nodeIds);
    }

    @Test
    void processRetryQueue_preservesTenantContextWhenRetryIsScheduledWithTenant() {
        RecordingEventPublisher publisher = new RecordingEventPublisher();
        InMemoryRetryManager manager = new InMemoryRetryManager();
        manager.eventPublisher = publisher;
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        TenantId tenantId = TenantId.of("tenant-1");
        NodeId nodeId = NodeId.of("node-1");

        manager.scheduleRetry(runId, tenantId, nodeId, 2, Duration.ZERO).await().indefinitely();
        manager.processRetryQueue();

        assertEquals(List.of(runId), publisher.runIds);
        assertEquals(List.of(tenantId), publisher.tenantIds);
        assertEquals(List.of(nodeId), publisher.nodeIds);
        assertEquals(List.of(2), publisher.attempts);
    }

    private static final class RecordingEventPublisher implements EventPublisher {
        final List<WorkflowRunId> runIds = new ArrayList<>();
        final List<TenantId> tenantIds = new ArrayList<>();
        final List<NodeId> nodeIds = new ArrayList<>();
        final List<Integer> attempts = new ArrayList<>();
        int failuresRemaining;

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
            return publishRetry(runId, null, nodeId);
        }

        @Override
        public Uni<Void> publishRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
            return publishRetry(runId, tenantId, nodeId, 0);
        }

        @Override
        public Uni<Void> publishRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
            runIds.add(runId);
            tenantIds.add(tenantId);
            nodeIds.add(nodeId);
            attempts.add(attempt);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return Uni.createFrom().failure(new IllegalStateException("event bus unavailable"));
            }
            return Uni.createFrom().voidItem();
        }
    }
}
