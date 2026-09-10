package tech.kayys.gamelan.dispatcher;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

@ApplicationScoped
@DefaultBean
public class InMemoryGrpcTaskStreamBroker implements GrpcTaskStreamBroker {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryGrpcTaskStreamBroker.class);
    private static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofMinutes(5);

    private final ConcurrentMap<String, ExecutorInbox> inboxes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> taskOwners = new ConcurrentHashMap<>();

    @ConfigProperty(name = "gamelan.grpc.task-stream.in-memory.ack-timeout", defaultValue = "5m")
    Duration ackTimeout = DEFAULT_ACK_TIMEOUT;

    LongSupplier clockMillis = System::currentTimeMillis;

    @Override
    public Uni<Void> assign(String executorId, NodeExecutionTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        if (executorId == null || executorId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("executorId cannot be blank"));
        }

        return Uni.createFrom().voidItem().invoke(() -> {
            String taskId = GrpcTaskStreamBroker.taskId(task);
            String existingOwner = taskOwners.putIfAbsent(taskId, executorId);
            if (existingOwner != null) {
                LOG.debug("Skipped duplicate gRPC stream task {} for executor {}; already owned by {}",
                        taskId, executorId, existingOwner);
                return;
            }

            ExecutorInbox inbox = inbox(executorId);
            boolean accepted = inbox.enqueue(new StreamedTask(taskId, task));
            if (accepted) {
                LOG.debug("Assigned gRPC stream task {} to executor {}", taskId, executorId);
            } else {
                taskOwners.remove(taskId, executorId);
                LOG.debug("Skipped duplicate gRPC stream task {} for executor {}", taskId, executorId);
            }
        });
    }

    @Override
    public Multi<StreamedTask> stream(String executorId, int maxConcurrent) {
        if (executorId == null || executorId.isBlank()) {
            return Multi.createFrom().failure(new IllegalArgumentException("executorId cannot be blank"));
        }

        int capacity = Math.max(1, maxConcurrent);
        ExecutorInbox inbox = inbox(executorId);
        return Multi.createFrom().emitter(emitter -> {
            ExecutorInbox.Subscription subscription = inbox.subscribe(emitter, capacity);
            emitter.onTermination(subscription::close);
        });
    }

    @Override
    public Uni<Void> acknowledge(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().voidItem().invoke(() -> {
            String executorId = taskOwners.get(taskId);
            ExecutorInbox inbox = executorId != null ? inboxes.get(executorId) : null;
            if (inbox != null) {
                inbox.acknowledge(taskId);
            }
        });
    }

    @Override
    public Uni<Void> complete(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().voidItem().invoke(() -> {
            String executorId = taskOwners.get(taskId);
            ExecutorInbox inbox = executorId != null ? inboxes.get(executorId) : null;
            if (inbox != null) {
                inbox.complete(taskId);
            }
            if (executorId != null) {
                taskOwners.remove(taskId, executorId);
            }
        });
    }

    private ExecutorInbox inbox(String executorId) {
        return inboxes.computeIfAbsent(executorId,
                ignored -> new ExecutorInbox(executorId, this::safeAckTimeoutMillis, this::nowMillis));
    }

    private long safeAckTimeoutMillis() {
        Duration timeout = ackTimeout;
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = DEFAULT_ACK_TIMEOUT;
        }
        return Math.max(1L, timeout.toMillis());
    }

    private long nowMillis() {
        return clockMillis != null ? clockMillis.getAsLong() : System.currentTimeMillis();
    }

    private static final class ExecutorInbox {
        private final String executorId;
        private final LongSupplier ackTimeoutMillis;
        private final LongSupplier clockMillis;
        private final ArrayDeque<StreamedTask> pending = new ArrayDeque<>();
        private final Set<String> pendingTaskIds = new HashSet<>();
        private final Map<String, StreamedTask> inFlightTasks = new HashMap<>();
        private final Map<String, Long> acknowledgedTaskDeadlines = new LinkedHashMap<>();
        private final List<Subscription> subscriptions = new ArrayList<>();
        private final Map<String, Subscription> taskSubscriptions = new HashMap<>();

        private ExecutorInbox(String executorId, LongSupplier ackTimeoutMillis, LongSupplier clockMillis) {
            this.executorId = executorId;
            this.ackTimeoutMillis = ackTimeoutMillis;
            this.clockMillis = clockMillis;
        }

        private synchronized boolean enqueue(StreamedTask task) {
            if (pendingTaskIds.contains(task.taskId()) || inFlightTasks.containsKey(task.taskId())) {
                return false;
            }
            pending.addLast(task);
            pendingTaskIds.add(task.taskId());
            drain();
            return true;
        }

        private synchronized Subscription subscribe(MultiEmitter<? super StreamedTask> emitter, int capacity) {
            Subscription subscription = new Subscription(this, emitter, capacity);
            subscriptions.add(subscription);
            drain();
            return subscription;
        }

        private synchronized void acknowledge(String taskId) {
            drain();
            if (inFlightTasks.containsKey(taskId)) {
                acknowledgedTaskDeadlines.put(taskId, nowMillis() + ackTimeoutMillis.getAsLong());
                LOG.trace("Executor {} acknowledged streamed task {}", executorId, taskId);
            }
        }

        private synchronized void complete(String taskId) {
            pending.removeIf(task -> task.taskId().equals(taskId));
            pendingTaskIds.remove(taskId);
            inFlightTasks.remove(taskId);
            acknowledgedTaskDeadlines.remove(taskId);
            Subscription subscription = taskSubscriptions.remove(taskId);
            if (subscription != null) {
                subscription.inFlightTaskIds.remove(taskId);
            }
            drain();
        }

        private synchronized void unsubscribe(Subscription subscription) {
            subscriptions.remove(subscription);
            List<StreamedTask> abandonedTasks = new ArrayList<>();
            for (String taskId : List.copyOf(subscription.inFlightTaskIds)) {
                if (acknowledgedTaskDeadlines.containsKey(taskId)) {
                    taskSubscriptions.remove(taskId, subscription);
                    continue;
                }
                StreamedTask task = inFlightTasks.remove(taskId);
                if (task != null) {
                    abandonedTasks.add(task);
                    taskSubscriptions.remove(taskId);
                }
            }
            for (int i = abandonedTasks.size() - 1; i >= 0; i--) {
                StreamedTask task = abandonedTasks.get(i);
                pending.addFirst(task);
                pendingTaskIds.add(task.taskId());
            }
            subscription.inFlightTaskIds.clear();
            drain();
        }

        private void drain() {
            requeueExpiredAcknowledgements();
            while (!pending.isEmpty() && hasGlobalCapacity()) {
                Subscription subscription = nextAvailableSubscription();
                if (subscription == null) {
                    return;
                }

                StreamedTask task = pending.removeFirst();
                pendingTaskIds.remove(task.taskId());
                inFlightTasks.put(task.taskId(), task);
                subscription.inFlightTaskIds.add(task.taskId());
                taskSubscriptions.put(task.taskId(), subscription);
                subscription.emit(task);
            }
        }

        private void requeueExpiredAcknowledgements() {
            long now = nowMillis();
            List<StreamedTask> expiredTasks = new ArrayList<>();
            for (Map.Entry<String, Long> entry : List.copyOf(acknowledgedTaskDeadlines.entrySet())) {
                if (entry.getValue() > now) {
                    continue;
                }

                String taskId = entry.getKey();
                acknowledgedTaskDeadlines.remove(taskId);
                StreamedTask task = inFlightTasks.remove(taskId);
                if (task == null) {
                    continue;
                }

                Subscription subscription = taskSubscriptions.remove(taskId);
                if (subscription != null) {
                    subscription.inFlightTaskIds.remove(taskId);
                }
                expiredTasks.add(task);
            }

            for (int i = expiredTasks.size() - 1; i >= 0; i--) {
                StreamedTask task = expiredTasks.get(i);
                pending.addFirst(task);
                pendingTaskIds.add(task.taskId());
                LOG.debug("Requeued expired acknowledged gRPC stream task {} for executor {}",
                        task.taskId(), executorId);
            }
        }

        private Subscription nextAvailableSubscription() {
            if (!hasGlobalCapacity()) {
                return null;
            }
            for (Subscription subscription : List.copyOf(subscriptions)) {
                if (subscription.hasCapacity()) {
                    return subscription;
                }
            }
            return null;
        }

        private boolean hasGlobalCapacity() {
            return inFlightTasks.size() < totalActiveCapacity();
        }

        private int totalActiveCapacity() {
            int capacity = 0;
            for (Subscription subscription : subscriptions) {
                capacity += subscription.capacity;
            }
            return capacity;
        }

        private long nowMillis() {
            return clockMillis.getAsLong();
        }

        private static final class Subscription {
            private final ExecutorInbox inbox;
            private final MultiEmitter<? super StreamedTask> emitter;
            private final int capacity;
            private final Set<String> inFlightTaskIds = new LinkedHashSet<>();

            private Subscription(ExecutorInbox inbox, MultiEmitter<? super StreamedTask> emitter, int capacity) {
                this.inbox = inbox;
                this.emitter = emitter;
                this.capacity = capacity;
            }

            private boolean hasCapacity() {
                return inFlightTaskIds.size() < capacity;
            }

            private void emit(StreamedTask task) {
                emitter.emit(task);
            }

            private void close() {
                inbox.unsubscribe(this);
            }
        }
    }
}
