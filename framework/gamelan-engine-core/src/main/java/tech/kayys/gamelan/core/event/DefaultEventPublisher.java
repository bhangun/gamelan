package tech.kayys.gamelan.core.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
public class DefaultEventPublisher implements EventPublisher {

    public static final String RUN_UPDATED_ADDRESS = WorkflowRunUpdateEvent.ADDRESS;
    public static final String RETRY_EVENT_TYPE = "NodeRetryRequested";

    private static final Logger LOG = LoggerFactory.getLogger(DefaultEventPublisher.class);

    @Inject
    PersistenceProvider persistence;
    @Inject
    ExtensionRegistry extensionRegistry;
    @Inject
    EventBus eventBus;
    @Inject
    Instance<WorkflowRunWakeupPublisher> wakeupPublishers;

    private final AtomicLong workflowEventsPublished = new AtomicLong();
    private final AtomicLong systemEventsPublished = new AtomicLong();
    private final AtomicLong batchEventsPublished = new AtomicLong();
    private final AtomicLong batchEventsSkipped = new AtomicLong();
    private final AtomicLong retryEventsPublished = new AtomicLong();
    private final AtomicLong interceptorFailures = new AtomicLong();
    private final AtomicLong persistenceAppendFailures = new AtomicLong();
    private final AtomicLong wakeupPublisherPublished = new AtomicLong();
    private final AtomicLong wakeupEventBusPublished = new AtomicLong();
    private final AtomicLong wakeupFallbackPublished = new AtomicLong();
    private final AtomicLong wakeupFailures = new AtomicLong();
    private final AtomicLong wakeupMissingPublisher = new AtomicLong();
    private volatile String lastFailure;
    private volatile Instant lastFailureAt;

    @Override
    public void publish(String eventType, Object payload, WorkflowContext wf) {
        Objects.requireNonNull(wf, "WorkflowContext cannot be null");

        appendEvent(wf.runId(), eventType, payload);
        workflowEventsPublished.incrementAndGet();

        interceptors().forEach(interceptor -> notifyWorkflowInterceptor(interceptor, eventType, payload, wf));
    }

    @Override
    public void publishSystem(String eventType, Object payload) {
        systemEventsPublished.incrementAndGet();
        interceptors().forEach(interceptor -> notifySystemInterceptor(interceptor, eventType, payload));
    }

    @Override
    public Uni<Void> publish(List<ExecutionEvent> events) {
        return Uni.createFrom().voidItem().invoke(() -> {
            if (events == null || events.isEmpty()) {
                return;
            }
            for (ExecutionEvent event : events) {
                if (event == null || event.runId() == null) {
                    batchEventsSkipped.incrementAndGet();
                    continue;
                }
                appendEvent(event.runId(), event.eventType(), event);
                batchEventsPublished.incrementAndGet();
            }
        });
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
        return Uni.createFrom().item(() -> {
            Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
            Objects.requireNonNull(nodeId, "NodeId cannot be null");

            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("nodeId", nodeId.value());
            if (tenantId != null) {
                metadata.put("tenantId", tenantId.value());
            }
            if (attempt > 0) {
                metadata.put("attempt", attempt);
            }

            GenericExecutionEvent event = new GenericExecutionEvent(
                    runId,
                    RETRY_EVENT_TYPE,
                    "Retry requested for node " + nodeId.value(),
                    Instant.now(),
                    Map.copyOf(metadata));

            appendEvent(runId, event.eventType(), event);
            retryEventsPublished.incrementAndGet();
            interceptors().forEach(interceptor -> notifySystemInterceptor(interceptor, event.eventType(), event));

            return WorkflowRunUpdateEvent.of(runId, tenantId, "retry-requested");
        }).call(this::publishWakeup).replaceWithVoid();
    }

    private Uni<Void> publishWakeup(WorkflowRunUpdateEvent event) {
        WorkflowRunWakeupPublisher publisher = wakeupPublisher();
        if (publisher != null) {
            return publisher.publish(event)
                    .invoke(() -> wakeupPublisherPublished.incrementAndGet())
                    .onFailure().recoverWithUni(error -> {
                        recordWakeupFailure("publisher", event, error);
                        return publishWakeupOnEventBus(event, true);
                    });
        }
        return publishWakeupOnEventBus(event, false);
    }

    private Uni<Void> publishWakeupOnEventBus(WorkflowRunUpdateEvent event, boolean fallback) {
        if (eventBus != null) {
            try {
                eventBus.publish(RUN_UPDATED_ADDRESS, JsonObject.mapFrom(event));
                wakeupEventBusPublished.incrementAndGet();
                if (fallback) {
                    wakeupFallbackPublished.incrementAndGet();
                }
            } catch (RuntimeException error) {
                recordWakeupFailure(fallback ? "event-bus-fallback" : "event-bus", event, error);
            }
            return Uni.createFrom().voidItem();
        }
        wakeupMissingPublisher.incrementAndGet();
        LOG.warn("No workflow run wake-up publisher available for run={}, reason={} fallback={}",
                event.runId(), event.reason(), fallback);
        return Uni.createFrom().voidItem();
    }

    private WorkflowRunWakeupPublisher wakeupPublisher() {
        if (wakeupPublishers == null || !wakeupPublishers.isResolvable()) {
            return null;
        }
        try {
            return wakeupPublishers.get();
        } catch (RuntimeException error) {
            recordWakeupFailure("publisher-resolution", null, error);
            return null;
        }
    }

    private void recordWakeupFailure(String channel, WorkflowRunUpdateEvent event, Throwable error) {
        wakeupFailures.incrementAndGet();
        recordLastFailure(error);
        LOG.warn("Workflow run wake-up publication failed channel={} run={} reason={} error={}",
                channel,
                event != null ? event.runId() : "<unknown>",
                event != null ? event.reason() : "<unknown>",
                lastFailure);
        LOG.debug("Workflow run wake-up publication failure details", error);
    }

    @Override
    public EventPublisherDiagnostics diagnostics() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("workflowEventsPublished", workflowEventsPublished.get());
        counters.put("systemEventsPublished", systemEventsPublished.get());
        counters.put("batchEventsPublished", batchEventsPublished.get());
        counters.put("batchEventsSkipped", batchEventsSkipped.get());
        counters.put("retryEventsPublished", retryEventsPublished.get());
        counters.put("interceptorFailures", interceptorFailures.get());
        counters.put("persistenceAppendFailures", persistenceAppendFailures.get());
        counters.put("wakeupPublisherPublished", wakeupPublisherPublished.get());
        counters.put("wakeupEventBusPublished", wakeupEventBusPublished.get());
        counters.put("wakeupFallbackPublished", wakeupFallbackPublished.get());
        counters.put("wakeupFailures", wakeupFailures.get());
        counters.put("wakeupMissingPublisher", wakeupMissingPublisher.get());
        return EventPublisherDiagnostics.available(
                getClass().getName(),
                counters,
                lastFailure,
                lastFailureAt);
    }

    private void appendEvent(WorkflowRunId runId, String eventType, Object payload) {
        try {
            persistence.appendEvent(runId, eventType, payload);
        } catch (RuntimeException error) {
            persistenceAppendFailures.incrementAndGet();
            recordLastFailure(error);
            LOG.warn("Workflow event persistence append failed run={} eventType={} error={}",
                    runId != null ? runId.value() : "<null>",
                    eventType,
                    lastFailure);
            LOG.debug("Workflow event persistence append failure details", error);
            throw error;
        }
    }

    private List<WorkflowInterceptor> interceptors() {
        if (extensionRegistry == null || extensionRegistry.interceptors() == null) {
            return List.of();
        }
        return List.copyOf(extensionRegistry.interceptors());
    }

    private void notifyWorkflowInterceptor(
            WorkflowInterceptor interceptor,
            String eventType,
            Object payload,
            WorkflowContext wf) {
        try {
            interceptor.onEvent(eventType, payload, wf);
        } catch (RuntimeException error) {
            recordInterceptorFailure(error);
            LOG.warn("Workflow event interceptor failed for eventType={} error={}: {}",
                    eventType,
                    error.getClass().getName(),
                    error.getMessage());
            LOG.debug("Workflow event interceptor failure details", error);
        }
    }

    private void notifySystemInterceptor(
            WorkflowInterceptor interceptor,
            String eventType,
            Object payload) {
        try {
            interceptor.onSystemEvent(eventType, payload);
        } catch (RuntimeException error) {
            recordInterceptorFailure(error);
            LOG.warn("System event interceptor failed for eventType={} error={}: {}",
                    eventType,
                    error.getClass().getName(),
                    error.getMessage());
            LOG.debug("System event interceptor failure details", error);
        }
    }

    private void recordInterceptorFailure(Throwable error) {
        interceptorFailures.incrementAndGet();
        recordLastFailure(error);
    }

    private void recordLastFailure(Throwable error) {
        lastFailure = errorSummary(error);
        lastFailureAt = Instant.now();
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
