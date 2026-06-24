package tech.kayys.gamelan.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisherDiagnostics;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class VertxWorkflowRunWakeupPublisherTest {

    private Vertx vertx;

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close().await().indefinitely();
        }
    }

    @Test
    void publish_whenEventBusUnavailableQueuesAndCoalescesWakeups() {
        VertxWorkflowRunWakeupPublisher publisher = new VertxWorkflowRunWakeupPublisher();
        WorkflowRunUpdateEvent event = event();

        publisher.publish(event).await().indefinitely();
        publisher.publish(event).await().indefinitely();

        assertEquals(1, publisher.pendingWakeupCount());
        WorkflowRunWakeupPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertEquals(2, diagnostics.counter("publishRequests"));
        assertEquals(2, diagnostics.counter("deliveryFailures"));
        assertEquals(0, diagnostics.counter("deliveredWakeups"));
        assertTrue(diagnostics.lastFailure().contains("event bus unavailable"));
    }

    @Test
    void publish_whenDirectDeliverySucceedsTracksDiagnostics() throws Exception {
        VertxWorkflowRunWakeupPublisher publisher = new VertxWorkflowRunWakeupPublisher();
        vertx = Vertx.vertx();
        publisher.eventBus = vertx.eventBus();
        CountDownLatch delivered = new CountDownLatch(1);
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> delivered.countDown());

        publisher.publish(event()).await().indefinitely();

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        WorkflowRunWakeupPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertTrue(diagnostics.available());
        assertEquals(1, diagnostics.counter("publishRequests"));
        assertEquals(1, diagnostics.counter("deliveredWakeups"));
        assertEquals(0, diagnostics.counter("deliveryFailures"));
        assertEquals(0, diagnostics.inFlightWakeups());
        assertEquals(256, diagnostics.drainBatchSize());
        assertEquals(32, diagnostics.deliveryParallelism());
    }

    @Test
    void publish_whenMultipleReasonsQueuedForSameRunCoalescesToLatestWakeup() throws Exception {
        VertxWorkflowRunWakeupPublisher publisher = new VertxWorkflowRunWakeupPublisher();

        publisher.publish(event("first-reason")).await().indefinitely();
        publisher.publish(event("second-reason")).await().indefinitely();

        assertEquals(1, publisher.pendingWakeupCount());
        VertxWorkflowRunWakeupPublisher.PendingWakeupSnapshot snapshot = publisher.pendingWakeupSnapshots().getFirst();
        assertEquals("run-1", snapshot.runId());
        assertEquals("tenant-1", snapshot.tenantId());
        assertEquals("second-reason", snapshot.reason());
        assertEquals(2, snapshot.attempts());

        vertx = Vertx.vertx();
        publisher.eventBus = vertx.eventBus();
        CountDownLatch delivered = new CountDownLatch(1);
        String[] deliveredReason = new String[1];
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> {
                    if (message.body() instanceof JsonObject json) {
                        deliveredReason[0] = json.getString("reason");
                        delivered.countDown();
                    }
                });

        publisher.drainPendingWakeups();

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertEquals("second-reason", deliveredReason[0]);
        assertEquals(0, publisher.pendingWakeupCount());
    }

    @Test
    void publish_whenDirectDeliverySucceedsClearsBufferedWakeupForSameRun() throws Exception {
        VertxWorkflowRunWakeupPublisher publisher = new VertxWorkflowRunWakeupPublisher();
        publisher.publish(event("stale-retry")).await().indefinitely();
        assertEquals(1, publisher.pendingWakeupCount());

        vertx = Vertx.vertx();
        publisher.eventBus = vertx.eventBus();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> deliveredReason = new AtomicReference<>();
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> {
                    if (message.body() instanceof JsonObject json) {
                        deliveredReason.set(json.getString("reason"));
                        delivered.countDown();
                    }
                });

        publisher.publish(event("fresh-wakeup")).await().indefinitely();

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertEquals("fresh-wakeup", deliveredReason.get());
        assertEquals(0, publisher.pendingWakeupCount());
    }

    @Test
    void drainPendingWakeups_redeliversQueuedWakeup() throws Exception {
        VertxWorkflowRunWakeupPublisher publisher = new VertxWorkflowRunWakeupPublisher();
        WorkflowRunUpdateEvent event = event();
        publisher.publish(event).await().indefinitely();

        vertx = Vertx.vertx();
        publisher.eventBus = vertx.eventBus();
        CountDownLatch delivered = new CountDownLatch(1);
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> {
                    if (message.body() instanceof JsonObject json
                            && event.runId().equals(json.getString("runId"))
                            && event.reason().equals(json.getString("reason"))) {
                        delivered.countDown();
                    }
                });

        publisher.drainPendingWakeups();

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertEquals(0, publisher.pendingWakeupCount());
    }

    @Test
    void drainPendingWakeups_skipsOverlappingDrainUntilActiveDrainSettles() throws Exception {
        VertxWorkflowRunWakeupPublisher publisher = new VertxWorkflowRunWakeupPublisher();
        BlockingClaimOutbox outbox = new BlockingClaimOutbox();
        publisher.outbox = outbox;

        publisher.drainPendingWakeups();
        await(() -> outbox.claims.get() == 1 && outbox.claimEmitter != null && publisher.drainInProgress());

        publisher.drainPendingWakeups();

        assertEquals(1, outbox.claims.get());
        WorkflowRunWakeupPublisherDiagnostics diagnostics = publisher.diagnostics();
        assertTrue(diagnostics.drainInProgress());
        assertEquals(1, diagnostics.counter("drainRuns"));
        assertEquals(1, diagnostics.counter("drainSkipped"));

        outbox.completeClaim(List.of());
        await(() -> !publisher.drainInProgress());

        publisher.drainPendingWakeups();
        await(() -> outbox.claims.get() == 2 && outbox.claimEmitter != null);

        outbox.completeClaim(List.of());
    }

    @Test
    void drainPendingWakeups_limitsConcurrentDeliveries() throws Exception {
        List<WorkflowRunWakeupIntent> intents = List.of(
                intent("queued-1", "run-1"),
                intent("queued-2", "run-2"),
                intent("queued-3", "run-3"),
                intent("queued-4", "run-4"));
        ControlledDeliveryOutbox outbox = new ControlledDeliveryOutbox(intents);
        VertxWorkflowRunWakeupPublisher publisher = new VertxWorkflowRunWakeupPublisher();
        publisher.outbox = outbox;
        publisher.deliveryParallelism = 2;
        publisher.drainBatchSize = intents.size();

        vertx = Vertx.vertx();
        publisher.eventBus = vertx.eventBus();

        publisher.drainPendingWakeups();

        await(() -> outbox.started.get() == 2);
        assertEquals(2, outbox.maxActive.get());

        outbox.completeOne();
        await(() -> outbox.started.get() == 3);
        assertTrue(outbox.maxActive.get() <= 2);

        outbox.completeAll();
        await(() -> !publisher.drainInProgress());

        assertEquals(intents.size(), outbox.started.get());
        assertEquals(intents.size(), outbox.completed.get());
        assertTrue(outbox.maxActive.get() <= 2);
    }

    private WorkflowRunUpdateEvent event() {
        return event("test-wakeup");
    }

    private WorkflowRunUpdateEvent event(String reason) {
        return event(reason, "run-1");
    }

    private WorkflowRunUpdateEvent event(String reason, String runId) {
        return WorkflowRunUpdateEvent.of(
                WorkflowRunId.of(runId),
                TenantId.of("tenant-1"),
                reason);
    }

    private WorkflowRunWakeupIntent intent(String reason, String runId) {
        return WorkflowRunWakeupIntent.pending(event(reason, runId), Instant.now());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static class BlockingClaimOutbox implements WorkflowRunWakeupOutbox {
        protected final AtomicInteger claims = new AtomicInteger();
        volatile UniEmitter<? super List<WorkflowRunWakeupIntent>> claimEmitter;

        @Override
        public Uni<WorkflowRunWakeupIntent> enqueue(WorkflowRunUpdateEvent event) {
            return Uni.createFrom().failure(new UnsupportedOperationException());
        }

        @Override
        public Uni<List<WorkflowRunWakeupIntent>> pending(int maxItems) {
            return Uni.createFrom().item(List.of());
        }

        @Override
        public Uni<List<WorkflowRunWakeupIntent>> claimPending(int maxItems) {
            claims.incrementAndGet();
            return Uni.createFrom().emitter(emitter -> claimEmitter = emitter);
        }

        @Override
        public Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> markFailed(String intentId, Throwable error) {
            return Uni.createFrom().voidItem();
        }

        private void completeClaim(List<WorkflowRunWakeupIntent> intents) {
            UniEmitter<? super List<WorkflowRunWakeupIntent>> emitter = claimEmitter;
            claimEmitter = null;
            emitter.complete(intents);
        }
    }

    private static final class ControlledDeliveryOutbox extends BlockingClaimOutbox {
        private final List<WorkflowRunWakeupIntent> intents;
        private final Queue<UniEmitter<? super Void>> deliveryCompletions = new ConcurrentLinkedQueue<>();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();

        private ControlledDeliveryOutbox(List<WorkflowRunWakeupIntent> intents) {
            this.intents = intents;
        }

        @Override
        public Uni<List<WorkflowRunWakeupIntent>> claimPending(int maxItems) {
            claims.incrementAndGet();
            return Uni.createFrom().item(intents);
        }

        @Override
        public Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent) {
            return Uni.createFrom().emitter(emitter -> {
                deliveryCompletions.add(emitter);
                int current = active.incrementAndGet();
                maxActive.updateAndGet(max -> Math.max(max, current));
                started.incrementAndGet();
            });
        }

        private void completeOne() {
            UniEmitter<? super Void> emitter = deliveryCompletions.poll();
            active.decrementAndGet();
            completed.incrementAndGet();
            emitter.complete(null);
        }

        private void completeAll() throws InterruptedException {
            while (completed.get() < intents.size()) {
                await(() -> !deliveryCompletions.isEmpty());
                completeOne();
            }
        }
    }
}
