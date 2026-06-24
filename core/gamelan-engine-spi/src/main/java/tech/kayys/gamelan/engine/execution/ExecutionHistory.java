package tech.kayys.gamelan.engine.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.CompensationCompletedEvent;
import tech.kayys.gamelan.engine.event.CompensationFailedEvent;
import tech.kayys.gamelan.engine.event.CompensationStartedEvent;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.NodeScheduledEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowCancelledEvent;
import tech.kayys.gamelan.engine.event.WorkflowCompletedEvent;
import tech.kayys.gamelan.engine.event.WorkflowFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowResumedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowSuspendedEvent;
import tech.kayys.gamelan.engine.node.NodeExecutionRecord;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.engine.workflow.WorkflowRunState;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Complete execution history of a workflow run.
 * Contains all events, state changes, and execution records.
 * Used for debugging, auditing, and replay capabilities.
 */
public class ExecutionHistory {

    public static final String DOMAIN_EVENT_PAYLOAD_METADATA_KEY = "domainEventPayload";

    private final WorkflowRunId runId;
    private final WorkflowId workflowId;
    private final String workflowVersion;
    private final String tenantId;
    private final Instant created;
    private final Instant lastUpdated;

    private final List<ExecutionEventHistory> events;
    private final List<NodeExecutionRecord> nodeExecutions;
    private final List<StateTransition> stateTransitions;

    private final Map<Instant, Map<String, Object>> inputSnapshots;
    private final Map<Instant, Map<String, Object>> outputSnapshots;

    private final ExecutionStatistics statistics;
    private final Map<String, Object> metadata;

    @JsonCreator
    public ExecutionHistory(
            @JsonProperty("runId") WorkflowRunId runId,
            @JsonProperty("workflowId") WorkflowId workflowId,
            @JsonProperty("workflowVersion") String workflowVersion,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("created") Instant created,
            @JsonProperty("lastUpdated") Instant lastUpdated,
            @JsonProperty("events") List<ExecutionEventHistory> events,
            @JsonProperty("nodeExecutions") List<NodeExecutionRecord> nodeExecutions,
            @JsonProperty("stateTransitions") List<StateTransition> stateTransitions,
            @JsonProperty("inputSnapshots") Map<Instant, Map<String, Object>> inputSnapshots,
            @JsonProperty("outputSnapshots") Map<Instant, Map<String, Object>> outputSnapshots,
            @JsonProperty("statistics") ExecutionStatistics statistics,
            @JsonProperty("metadata") Map<String, Object> metadata) {

        this.runId = runId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.tenantId = tenantId;
        this.created = created != null ? created : Instant.now();
        this.lastUpdated = lastUpdated != null ? lastUpdated : Instant.now();
        this.events = events != null ? List.copyOf(events) : List.of();
        this.nodeExecutions = nodeExecutions != null ? List.copyOf(nodeExecutions) : List.of();
        this.stateTransitions = stateTransitions != null ? List.copyOf(stateTransitions) : List.of();
        this.inputSnapshots = payloadSnapshots(inputSnapshots);
        this.outputSnapshots = payloadSnapshots(outputSnapshots);
        this.statistics = statistics != null ? statistics : ExecutionStatistics.empty();
        this.metadata = ExecutionPayloads.immutableMap(metadata);
    }

    public WorkflowRunId getRunId() {
        return runId;
    }

    public WorkflowId getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Instant getCreated() {
        return created;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public List<ExecutionEventHistory> getEvents() {
        return events;
    }

    public List<NodeExecutionRecord> getNodeExecutions() {
        return nodeExecutions;
    }

    public List<StateTransition> getStateTransitions() {
        return stateTransitions;
    }

    public Map<Instant, Map<String, Object>> getInputSnapshots() {
        return inputSnapshots;
    }

    public Map<Instant, Map<String, Object>> getOutputSnapshots() {
        return outputSnapshots;
    }

    public ExecutionStatistics getStatistics() {
        return statistics;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public static ExecutionHistory empty(WorkflowRunId runId, WorkflowId workflowId, String tenantId) {
        return ExecutionHistory.builder()
                .runId(runId)
                .workflowId(workflowId)
                .tenantId(tenantId)
                .created(Instant.now())
                .lastUpdated(Instant.now())
                .events(new ArrayList<>())
                .nodeExecutions(new ArrayList<>())
                .stateTransitions(new ArrayList<>())
                .inputSnapshots(new LinkedHashMap<>())
                .outputSnapshots(new LinkedHashMap<>())
                .statistics(ExecutionStatistics.empty())
                .metadata(Map.of("initialized", true))
                .build();
    }

    public static ExecutionHistory fromEvents(
            WorkflowRunId runId,
            List<ExecutionEvent> domainEvents) {

        List<ExecutionEvent> safeDomainEvents = ExecutionEventEnvelopes.validateForRun(runId, domainEvents);
        List<ExecutionEventHistory> historyEvents = safeDomainEvents.stream()
                .map(domainEvent -> ExecutionEventHistory.builder()
                        .eventId(domainEvent.eventId())
                        .eventType(mapEventType(safeEventType(domainEvent)))
                        .timestamp(domainEvent.occurredAt() != null ? domainEvent.occurredAt() : Instant.now())
                        .source("event-store")
                        .payload(domainEventPayload(domainEvent))
                        .metadata(domainEventMetadata(domainEvent))
                        .build())
                .toList();
        Instant created = historyEvents.isEmpty() ? Instant.now() : historyEvents.get(0).getTimestamp();
        Instant lastUpdated = historyEvents.isEmpty() ? created : historyEvents.get(historyEvents.size() - 1)
                .getTimestamp();
        WorkflowIdentity workflowIdentity = inferWorkflowIdentity(safeDomainEvents, historyEvents);
        String tenantId = inferTenantId(safeDomainEvents, historyEvents);

        return ExecutionHistory.builder()
                .runId(runId)
                .workflowId(WorkflowId.of(workflowIdentity.workflowId()))
                .workflowVersion(workflowIdentity.workflowVersion())
                .tenantId(tenantId)
                .created(created)
                .lastUpdated(lastUpdated)
                .events(historyEvents)
                .nodeExecutions(new ArrayList<>())
                .stateTransitions(new ArrayList<>())
                .inputSnapshots(new LinkedHashMap<>())
                .outputSnapshots(new LinkedHashMap<>())
                .statistics(ExecutionStatistics.builder().totalEvents(historyEvents.size()).build())
                .metadata(Map.of("source", "domain-events"))
                .build();
    }

    private static WorkflowIdentity inferWorkflowIdentity(
            List<ExecutionEvent> domainEvents,
            List<ExecutionEventHistory> historyEvents) {

        String workflowId = null;
        String workflowVersion = null;
        for (ExecutionEvent event : domainEvents) {
            workflowId = firstPresent(workflowId, workflowIdFromEvent(event));
            workflowVersion = firstPresent(workflowVersion, workflowVersionFromEvent(event));
            if (workflowId != null && workflowVersion != null) {
                return new WorkflowIdentity(workflowId, workflowVersion);
            }
        }
        for (ExecutionEventHistory event : historyEvents) {
            workflowId = firstPresent(workflowId, workflowIdFromMetadata(event.getMetadata()));
            workflowVersion = firstPresent(workflowVersion, workflowVersionFromMetadata(event.getMetadata()));
            if (workflowId != null && workflowVersion != null) {
                return new WorkflowIdentity(workflowId, workflowVersion);
            }
        }
        return new WorkflowIdentity(
                workflowId != null ? workflowId : "unknown",
                workflowVersion != null ? workflowVersion : "unknown");
    }

    private static String workflowIdFromEvent(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent started) {
            return stringValue(started.definitionId());
        }
        if (event instanceof GenericExecutionEvent generic) {
            return workflowIdFromMetadata(generic.metadata());
        }
        return null;
    }

    private static String workflowVersionFromEvent(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent started) {
            return stringValue(started.workflowVersion());
        }
        if (event instanceof GenericExecutionEvent generic) {
            return workflowVersionFromMetadata(generic.metadata());
        }
        return null;
    }

    private static String workflowIdFromMetadata(Map<String, Object> metadata) {
        return firstPresent(
                metadataValue(metadata, "workflowId"),
                metadataValue(metadata, "definitionId"),
                metadataValue(metadata, "workflowDefinitionId"));
    }

    private static String workflowVersionFromMetadata(Map<String, Object> metadata) {
        return firstPresent(
                metadataValue(metadata, "workflowVersion"),
                metadataValue(metadata, "definitionVersion"));
    }

    private static String inferTenantId(
            List<ExecutionEvent> domainEvents,
            List<ExecutionEventHistory> historyEvents) {

        for (ExecutionEvent event : domainEvents) {
            String tenantId = tenantIdFromEvent(event);
            if (tenantId != null) {
                return tenantId;
            }
        }
        for (ExecutionEventHistory event : historyEvents) {
            String tenantId = tenantIdFromMetadata(event.getMetadata());
            if (tenantId != null) {
                return tenantId;
            }
        }
        return "unknown";
    }

    private static String tenantIdFromEvent(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent started) {
            return stringValue(started.tenantId());
        }
        if (event instanceof CompensationStartedEvent started) {
            return stringValue(started.tenantId());
        }
        if (event instanceof CompensationCompletedEvent completed) {
            return stringValue(completed.tenantId());
        }
        if (event instanceof CompensationFailedEvent failed) {
            return stringValue(failed.tenantId());
        }
        if (event instanceof GenericExecutionEvent generic) {
            return tenantIdFromMetadata(generic.metadata());
        }
        return null;
    }

    private static String tenantIdFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return metadataValue(metadata, "tenantId");
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        return stringValue(metadata.get(key));
    }

    private static String firstPresent(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof WorkflowDefinitionId id) {
            return id.value();
        }
        if (value instanceof WorkflowId id) {
            return id.getId();
        }
        if (value instanceof WorkflowRunId id) {
            return id.value();
        }
        if (value instanceof TenantId id) {
            return id.value();
        }
        if (value instanceof NodeId id) {
            return id.value();
        }
        if (value instanceof Map<?, ?> map) {
            String byValue = stringValue(map.get("value"));
            return byValue != null ? byValue : stringValue(map.get("id"));
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static ExecutionEventHistory.ExecutionEventType mapEventType(String eventType) {
        return switch (eventType) {
            case "WorkflowStartedEvent", "WorkflowStarted" -> ExecutionEventHistory.ExecutionEventType.RUN_STARTED;
            case "NodeScheduledEvent", "NodeScheduled" -> ExecutionEventHistory.ExecutionEventType.NODE_STARTED;
            case "NodeStartedEvent", "NodeStarted" -> ExecutionEventHistory.ExecutionEventType.NODE_STARTED;
            case "NodeCompletedEvent", "NodeCompleted" -> ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED;
            case "NodeFailedEvent", "NodeFailed" -> ExecutionEventHistory.ExecutionEventType.NODE_FAILED;
            case "WorkflowSuspendedEvent", "WorkflowSuspended" -> ExecutionEventHistory.ExecutionEventType.RUN_WAITING;
            case "WorkflowResumedEvent", "WorkflowResumed" -> ExecutionEventHistory.ExecutionEventType.RUN_RESUMED;
            case "WorkflowCompletedEvent", "WorkflowCompleted" -> ExecutionEventHistory.ExecutionEventType.RUN_COMPLETED;
            case "WorkflowFailedEvent", "WorkflowFailed" -> ExecutionEventHistory.ExecutionEventType.RUN_FAILED;
            case "WorkflowCancelledEvent", "WorkflowCancelled" -> ExecutionEventHistory.ExecutionEventType.RUN_CANCELLED;
            case "CompensationStartedEvent", "CompensationStarted" ->
                ExecutionEventHistory.ExecutionEventType.COMPENSATION_STARTED;
            case "CompensationCompletedEvent", "CompensationCompleted" ->
                ExecutionEventHistory.ExecutionEventType.COMPENSATION_COMPLETED;
            case "CompensationFailedEvent", "CompensationFailed" ->
                ExecutionEventHistory.ExecutionEventType.ERROR_OCCURRED;
            case "SIGNAL_RECEIVED", "SignalReceived", "SignalReceivedEvent" ->
                ExecutionEventHistory.ExecutionEventType.SIGNAL_RECEIVED;
            case "SIGNAL_IGNORED", "SignalIgnored", "SignalIgnoredEvent" ->
                ExecutionEventHistory.ExecutionEventType.SIGNAL_IGNORED;
            default -> ExecutionEventHistory.ExecutionEventType.STATE_UPDATED;
        };
    }

    private static Map<String, Object> domainEventPayload(ExecutionEvent event) {
        if (event instanceof GenericExecutionEvent generic) {
            Map<String, Object> payload = metadataMap(generic.metadata().get(DOMAIN_EVENT_PAYLOAD_METADATA_KEY));
            if (!payload.isEmpty()) {
                return payload;
            }
            return generic.message() != null ? Map.of("message", generic.message()) : Map.of();
        }
        if (event instanceof WorkflowStartedEvent started) {
            return started.inputs();
        }
        if (event instanceof NodeCompletedEvent completed) {
            return completed.output();
        }
        if (event instanceof NodeFailedEvent failed) {
            return errorPayload(failed.error());
        }
        if (event instanceof WorkflowResumedEvent resumed) {
            return resumed.resumeData();
        }
        if (event instanceof WorkflowCompletedEvent completed) {
            return completed.outputs();
        }
        if (event instanceof WorkflowFailedEvent failed) {
            return errorPayload(failed.error());
        }
        if (event instanceof CompensationFailedEvent failed) {
            return errorPayload(failed.error());
        }
        return Map.of();
    }

    private static Map<String, Object> domainEventMetadata(ExecutionEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("domainEventType", safeEventType(event));
        metadata.put("domainEventClass", event.getClass().getSimpleName());
        if (event.runId() != null) {
            metadata.put("runId", event.runId().value());
        }
        if (event instanceof GenericExecutionEvent generic) {
            copyGenericMetadata(metadata, generic.metadata());
        } else if (event instanceof WorkflowStartedEvent started) {
            putValue(metadata, "definitionId", started.definitionId());
            putValue(metadata, "workflowVersion", started.workflowVersion());
            putValue(metadata, "tenantId", started.tenantId());
        } else if (event instanceof NodeScheduledEvent scheduled) {
            putNode(metadata, scheduled.nodeId());
            metadata.put("attempt", scheduled.attempt());
        } else if (event instanceof NodeStartedEvent started) {
            putNode(metadata, started.nodeId());
            metadata.put("attempt", started.attempt());
        } else if (event instanceof NodeCompletedEvent completed) {
            putNode(metadata, completed.nodeId());
            metadata.put("attempt", completed.attempt());
        } else if (event instanceof NodeFailedEvent failed) {
            putNode(metadata, failed.nodeId());
            metadata.put("attempt", failed.attempt());
            metadata.put("willRetry", failed.willRetry());
            putValue(metadata, "retryAt", failed.retryAt());
        } else if (event instanceof WorkflowSuspendedEvent suspended) {
            putValue(metadata, "reason", suspended.reason());
            putNode(metadata, "waitingOnNodeId", suspended.waitingOnNodeId());
        } else if (event instanceof WorkflowResumedEvent resumed) {
            putValue(metadata, "humanTaskId", resumed.humanTaskId());
        } else if (event instanceof WorkflowCancelledEvent cancelled) {
            putValue(metadata, "reason", cancelled.reason());
        } else if (event instanceof CompensationStartedEvent started) {
            putValue(metadata, "tenantId", started.tenantId());
            metadata.put("nodesToCompensate", nodeValues(started.nodesToCompensate()));
        } else if (event instanceof CompensationCompletedEvent completed) {
            putValue(metadata, "tenantId", completed.tenantId());
            metadata.put("compensatedNodes", nodeValues(completed.compensatedNodes()));
        } else if (event instanceof CompensationFailedEvent failed) {
            putValue(metadata, "tenantId", failed.tenantId());
        }
        return metadata;
    }

    private static void copyGenericMetadata(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((key, value) -> {
            if (!DOMAIN_EVENT_PAYLOAD_METADATA_KEY.equals(key)) {
                target.put(key, value);
            }
        });
    }

    private static Map<String, Object> metadataMap(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, mapValue) -> {
            if (key != null) {
                copy.put(String.valueOf(key), mapValue);
            }
        });
        return copy;
    }

    private static String safeEventType(ExecutionEvent event) {
        return event.eventType() != null && !event.eventType().isBlank() ? event.eventType() : "Unknown";
    }

    private static Map<String, Object> errorPayload(ErrorInfo error) {
        if (error == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putValue(payload, "code", error.code());
        putValue(payload, "message", error.message());
        putValue(payload, "stackTrace", error.stackTrace());
        payload.put("context", error.context());
        return payload;
    }

    private static List<String> nodeValues(List<NodeId> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        return nodeIds.stream().map(NodeId::value).toList();
    }

    private static void putNode(Map<String, Object> metadata, NodeId nodeId) {
        putNode(metadata, "nodeId", nodeId);
    }

    private static void putNode(Map<String, Object> metadata, String key, NodeId nodeId) {
        if (nodeId != null) {
            metadata.put(key, nodeId.value());
        }
    }

    private static void putValue(Map<String, Object> metadata, String key, Object value) {
        String text = stringValue(value);
        if (text != null) {
            metadata.put(key, text);
        }
    }

    private record WorkflowIdentity(String workflowId, String workflowVersion) {
    }

    private static Map<Instant, Map<String, Object>> payloadSnapshots(
            Map<Instant, Map<String, Object>> snapshots) {

        if (snapshots == null || snapshots.isEmpty()) {
            return Map.of();
        }

        Map<Instant, Map<String, Object>> copy = new LinkedHashMap<>();
        snapshots.forEach((instant, payload) -> copy.put(instant, ExecutionPayloads.immutableMap(payload)));
        return Collections.unmodifiableMap(copy);
    }

    public static ExecutionHistory fromSnapshot(
            WorkflowRunSnapshot snapshot,
            List<ExecutionEventHistory> events,
            List<NodeExecutionRecord> nodeExecutions) {

        return ExecutionHistory.builder()
                .runId(snapshot.id())
                .workflowId(WorkflowId.of(snapshot.definitionId().value()))
                .workflowVersion(snapshot.definitionVersion())
                .tenantId(snapshot.tenantId().value())
                .created(snapshot.createdAt())
                .lastUpdated(Instant.now())
                .events(events)
                .nodeExecutions(nodeExecutions)
                .stateTransitions(List.of())
                .inputSnapshots(Map.of(
                        snapshot.createdAt(),
                        snapshot.variables()))
                .outputSnapshots(Map.of())
                .statistics(ExecutionStatistics.builder().build())
                .metadata(Map.of("source", "snapshot"))
                .build();
    }

    public ExecutionHistory addEvent(ExecutionEventHistory event) {
        List<ExecutionEventHistory> newEvents = new ArrayList<>(this.events);
        newEvents.add(event);

        return this.toBuilder()
                .events(newEvents)
                .lastUpdated(Instant.now())
                .build();
    }

    public ExecutionHistory addNodeExecution(NodeExecutionRecord record) {
        List<NodeExecutionRecord> newExecutions = new ArrayList<>(this.nodeExecutions);
        newExecutions.add(record);

        return this.toBuilder()
                .nodeExecutions(newExecutions)
                .statistics(statistics.merge(record))
                .lastUpdated(Instant.now())
                .build();
    }

    public Optional<StateTransition> getLastStateTransition() {
        if (stateTransitions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(stateTransitions.get(stateTransitions.size() - 1));
    }

    public Optional<WorkflowRunState> getCurrentState() {
        return getLastStateTransition()
                .map(StateTransition::getToState);
    }

    public boolean hasErrors() {
        return nodeExecutions.stream()
                .anyMatch(record -> record.getStatus() == NodeExecutionStatus.FAILED) ||
                events.stream()
                        .anyMatch(
                                event -> event.getEventType() == ExecutionEventHistory.ExecutionEventType.ERROR_OCCURRED
                                        ||
                                        event.getEventType() == ExecutionEventHistory.ExecutionEventType.RUN_FAILED ||
                                        event.getEventType() == ExecutionEventHistory.ExecutionEventType.NODE_FAILED);
    }

    public Duration getTotalDuration() {
        if (events.isEmpty()) {
            return Duration.ZERO;
        }

        Instant firstEvent = events.get(0).getTimestamp();
        Instant lastEvent = events.get(events.size() - 1).getTimestamp();

        return Duration.between(firstEvent, lastEvent);
    }

    public boolean isComplete() {
        return getCurrentState()
                .map(state -> state.isTerminal())
                .orElse(false);
    }

    public static class ExecutionEventHistory {
        public enum ExecutionEventType {
            RUN_STARTED, RUN_COMPLETED, RUN_FAILED, RUN_CANCELLED, RUN_WAITING, RUN_RESUMED,
            NODE_STARTED, NODE_COMPLETED, NODE_FAILED, NODE_WAITING, STATE_UPDATED, SIGNAL_RECEIVED, SIGNAL_IGNORED,
            ERROR_OCCURRED, COMPENSATION_STARTED, COMPENSATION_COMPLETED, RETRY_SCHEDULED,
            TIMER_EXPIRED, EXTERNAL_CALLBACK_RECEIVED, HUMAN_INTERVENTION_REQUIRED, HUMAN_INTERVENTION_COMPLETED
        }

        private final String eventId;
        private final ExecutionEventType eventType;
        private final Instant timestamp;
        private final String source;
        private final Map<String, Object> payload;
        private final Map<String, Object> metadata;
        private final ExecutionError error;

        public ExecutionEventHistory(String eventId, ExecutionEventType eventType, Instant timestamp,
                String source, Map<String, Object> payload, Map<String, Object> metadata,
                ExecutionError error) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.timestamp = timestamp;
            this.source = source;
            this.payload = ExecutionPayloads.immutableMap(payload);
            this.metadata = ExecutionPayloads.immutableMap(metadata);
            this.error = error;
        }

        public String getEventId() {
            return eventId;
        }

        public ExecutionEventType getEventType() {
            return eventType;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public String getSource() {
            return source;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public ExecutionError getError() {
            return error;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String eventId;
            private ExecutionEventType eventType;
            private Instant timestamp;
            private String source;
            private Map<String, Object> payload;
            private Map<String, Object> metadata;
            private ExecutionError error;

            public Builder eventId(String eventId) {
                this.eventId = eventId;
                return this;
            }

            public Builder eventType(ExecutionEventType eventType) {
                this.eventType = eventType;
                return this;
            }

            public Builder timestamp(Instant timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public Builder source(String source) {
                this.source = source;
                return this;
            }

            public Builder payload(Map<String, Object> payload) {
                this.payload = payload;
                return this;
            }

            public Builder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            public Builder error(ExecutionError error) {
                this.error = error;
                return this;
            }

            public ExecutionEventHistory build() {
                return new ExecutionEventHistory(eventId, eventType, timestamp, source, payload, metadata, error);
            }
        }
    }

    public static class StateTransition {
        private final WorkflowRunState fromState;
        private final WorkflowRunState toState;
        private final Instant timestamp;
        private final String reason;
        private final String initiatedBy;
        private final Map<String, Object> metadata;

        public StateTransition(WorkflowRunState fromState, WorkflowRunState toState, Instant timestamp,
                String reason, String initiatedBy, Map<String, Object> metadata) {
            this.fromState = fromState;
            this.toState = toState;
            this.timestamp = timestamp;
            this.reason = reason;
            this.initiatedBy = initiatedBy;
            this.metadata = ExecutionPayloads.immutableMap(metadata);
        }

        public WorkflowRunState getFromState() {
            return fromState;
        }

        public WorkflowRunState getToState() {
            return toState;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public String getReason() {
            return reason;
        }

        public String getInitiatedBy() {
            return initiatedBy;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private WorkflowRunState fromState;
            private WorkflowRunState toState;
            private Instant timestamp;
            private String reason;
            private String initiatedBy;
            private Map<String, Object> metadata;

            public Builder fromState(WorkflowRunState fromState) {
                this.fromState = fromState;
                return this;
            }

            public Builder toState(WorkflowRunState toState) {
                this.toState = toState;
                return this;
            }

            public Builder timestamp(Instant timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public Builder reason(String reason) {
                this.reason = reason;
                return this;
            }

            public Builder initiatedBy(String initiatedBy) {
                this.initiatedBy = initiatedBy;
                return this;
            }

            public Builder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            public StateTransition build() {
                return new StateTransition(fromState, toState, timestamp, reason, initiatedBy, metadata);
            }
        }
    }

    public static class ExecutionStatistics {
        private final int totalEvents;
        private final int totalNodeExecutions;
        private final int completedNodes;
        private final int failedNodes;
        private final int waitingNodes;
        private final int retriedNodes;
        private final Duration totalExecutionTime;
        private final Duration averageNodeExecutionTime;
        private final Map<String, Integer> nodeTypeCounts;
        private final Map<String, Duration> nodeTypeDurations;
        private final Map<String, Object> metrics;

        public ExecutionStatistics(int totalEvents, int totalNodeExecutions, int completedNodes,
                int failedNodes, int waitingNodes, int retriedNodes,
                Duration totalExecutionTime, Duration averageNodeExecutionTime,
                Map<String, Integer> nodeTypeCounts, Map<String, Duration> nodeTypeDurations,
                Map<String, Object> metrics) {
            this.totalEvents = totalEvents;
            this.totalNodeExecutions = totalNodeExecutions;
            this.completedNodes = completedNodes;
            this.failedNodes = failedNodes;
            this.waitingNodes = waitingNodes;
            this.retriedNodes = retriedNodes;
            this.totalExecutionTime = totalExecutionTime != null ? totalExecutionTime : Duration.ZERO;
            this.averageNodeExecutionTime = averageNodeExecutionTime != null ? averageNodeExecutionTime : Duration.ZERO;
            this.nodeTypeCounts = nodeTypeCounts != null ? Map.copyOf(nodeTypeCounts) : Map.of();
            this.nodeTypeDurations = nodeTypeDurations != null ? Map.copyOf(nodeTypeDurations) : Map.of();
            this.metrics = ExecutionPayloads.immutableMap(metrics);
        }

        public int getTotalEvents() {
            return totalEvents;
        }

        public int getTotalNodeExecutions() {
            return totalNodeExecutions;
        }

        public int getCompletedNodes() {
            return completedNodes;
        }

        public int getFailedNodes() {
            return failedNodes;
        }

        public int getWaitingNodes() {
            return waitingNodes;
        }

        public int getRetriedNodes() {
            return retriedNodes;
        }

        public Duration getTotalExecutionTime() {
            return totalExecutionTime;
        }

        public Duration getAverageNodeExecutionTime() {
            return averageNodeExecutionTime;
        }

        public Map<String, Integer> getNodeTypeCounts() {
            return nodeTypeCounts;
        }

        public Map<String, Duration> getNodeTypeDurations() {
            return nodeTypeDurations;
        }

        public Map<String, Object> getMetrics() {
            return metrics;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return builder()
                    .totalEvents(totalEvents)
                    .totalNodeExecutions(totalNodeExecutions)
                    .completedNodes(completedNodes)
                    .failedNodes(failedNodes)
                    .waitingNodes(waitingNodes)
                    .retriedNodes(retriedNodes)
                    .totalExecutionTime(totalExecutionTime)
                    .averageNodeExecutionTime(averageNodeExecutionTime)
                    .nodeTypeCounts(nodeTypeCounts)
                    .nodeTypeDurations(nodeTypeDurations)
                    .metrics(metrics);
        }

        public static ExecutionStatistics empty() {
            return builder().build();
        }

        public ExecutionStatistics merge(NodeExecutionRecord record) {
            Map<String, Integer> newNodeTypeCounts = new HashMap<>(nodeTypeCounts);
            Map<String, Duration> newNodeTypeDurations = new HashMap<>(nodeTypeDurations);

            String nodeType = record.getMetadata() != null
                    ? (String) record.getMetadata().getOrDefault("nodeType", "unknown")
                    : "unknown";

            newNodeTypeCounts.put(nodeType, newNodeTypeCounts.getOrDefault(nodeType, 0) + 1);

            Duration currentDuration = newNodeTypeDurations.getOrDefault(nodeType, Duration.ZERO);
            Duration recordDuration = record.getDuration() != null ? record.getDuration() : Duration.ZERO;
            newNodeTypeDurations.put(nodeType, currentDuration.plus(recordDuration));

            int newTotalNodeExecutions = totalNodeExecutions + 1;
            int newCompletedNodes = completedNodes + (record.getStatus() == NodeExecutionStatus.COMPLETED ? 1 : 0);
            int newFailedNodes = failedNodes + (record.getStatus() == NodeExecutionStatus.FAILED ? 1 : 0);
            int newWaitingNodes = waitingNodes + (record.getStatus() == NodeExecutionStatus.WAITING ? 1 : 0);
            int newRetriedNodes = retriedNodes + (record.getAttempt() > 1 ? 1 : 0);

            Duration newTotalExecutionTime = totalExecutionTime.plus(recordDuration);
            Duration newAverageNodeExecutionTime = newTotalNodeExecutions > 0
                    ? newTotalExecutionTime.dividedBy(newTotalNodeExecutions)
                    : Duration.ZERO;

            return this.toBuilder()
                    .totalNodeExecutions(newTotalNodeExecutions)
                    .completedNodes(newCompletedNodes)
                    .failedNodes(newFailedNodes)
                    .waitingNodes(newWaitingNodes)
                    .retriedNodes(newRetriedNodes)
                    .totalExecutionTime(newTotalExecutionTime)
                    .averageNodeExecutionTime(newAverageNodeExecutionTime)
                    .nodeTypeCounts(newNodeTypeCounts)
                    .nodeTypeDurations(newNodeTypeDurations)
                    .build();
        }

        public ExecutionStatistics merge(ExecutionEventHistory event) {
            return this.toBuilder()
                    .totalEvents(totalEvents + 1)
                    .build();
        }

        public double getSuccessRate() {
            if (totalNodeExecutions == 0)
                return 1.0;
            return (double) completedNodes / totalNodeExecutions;
        }

        public static class Builder {
            private int totalEvents = 0;
            private int totalNodeExecutions = 0;
            private int completedNodes = 0;
            private int failedNodes = 0;
            private int waitingNodes = 0;
            private int retriedNodes = 0;
            private Duration totalExecutionTime = Duration.ZERO;
            private Duration averageNodeExecutionTime = Duration.ZERO;
            private Map<String, Integer> nodeTypeCounts = Map.of();
            private Map<String, Duration> nodeTypeDurations = Map.of();
            private Map<String, Object> metrics = Map.of();

            public Builder totalEvents(int totalEvents) {
                this.totalEvents = totalEvents;
                return this;
            }

            public Builder totalNodeExecutions(int totalNodeExecutions) {
                this.totalNodeExecutions = totalNodeExecutions;
                return this;
            }

            public Builder completedNodes(int completedNodes) {
                this.completedNodes = completedNodes;
                return this;
            }

            public Builder failedNodes(int failedNodes) {
                this.failedNodes = failedNodes;
                return this;
            }

            public Builder waitingNodes(int waitingNodes) {
                this.waitingNodes = waitingNodes;
                return this;
            }

            public Builder retriedNodes(int retriedNodes) {
                this.retriedNodes = retriedNodes;
                return this;
            }

            public Builder totalExecutionTime(Duration totalExecutionTime) {
                this.totalExecutionTime = totalExecutionTime;
                return this;
            }

            public Builder averageNodeExecutionTime(Duration averageNodeExecutionTime) {
                this.averageNodeExecutionTime = averageNodeExecutionTime;
                return this;
            }

            public Builder nodeTypeCounts(Map<String, Integer> nodeTypeCounts) {
                this.nodeTypeCounts = nodeTypeCounts;
                return this;
            }

            public Builder nodeTypeDurations(Map<String, Duration> nodeTypeDurations) {
                this.nodeTypeDurations = nodeTypeDurations;
                return this;
            }

            public Builder metrics(Map<String, Object> metrics) {
                this.metrics = metrics;
                return this;
            }

            public ExecutionStatistics build() {
                return new ExecutionStatistics(totalEvents, totalNodeExecutions, completedNodes, failedNodes,
                        waitingNodes, retriedNodes, totalExecutionTime, averageNodeExecutionTime,
                        nodeTypeCounts, nodeTypeDurations, metrics);
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .runId(runId)
                .workflowId(workflowId)
                .workflowVersion(workflowVersion)
                .tenantId(tenantId)
                .created(created)
                .lastUpdated(lastUpdated)
                .events(events)
                .nodeExecutions(nodeExecutions)
                .stateTransitions(stateTransitions)
                .inputSnapshots(inputSnapshots)
                .outputSnapshots(outputSnapshots)
                .statistics(statistics)
                .metadata(metadata);
    }

    public static class Builder {
        private WorkflowRunId runId;
        private WorkflowId workflowId;
        private String workflowVersion;
        private String tenantId;
        private Instant created;
        private Instant lastUpdated;
        private List<ExecutionEventHistory> events;
        private List<NodeExecutionRecord> nodeExecutions;
        private List<StateTransition> stateTransitions;
        private Map<Instant, Map<String, Object>> inputSnapshots;
        private Map<Instant, Map<String, Object>> outputSnapshots;
        private ExecutionStatistics statistics;
        private Map<String, Object> metadata;

        public Builder runId(WorkflowRunId runId) {
            this.runId = runId;
            return this;
        }

        public Builder workflowId(WorkflowId workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder workflowVersion(String workflowVersion) {
            this.workflowVersion = workflowVersion;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder created(Instant created) {
            this.created = created;
            return this;
        }

        public Builder lastUpdated(Instant lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        public Builder events(List<ExecutionEventHistory> events) {
            this.events = events;
            return this;
        }

        public Builder nodeExecutions(List<NodeExecutionRecord> nodeExecutions) {
            this.nodeExecutions = nodeExecutions;
            return this;
        }

        public Builder stateTransitions(List<StateTransition> stateTransitions) {
            this.stateTransitions = stateTransitions;
            return this;
        }

        public Builder inputSnapshots(Map<Instant, Map<String, Object>> inputSnapshots) {
            this.inputSnapshots = inputSnapshots;
            return this;
        }

        public Builder outputSnapshots(Map<Instant, Map<String, Object>> outputSnapshots) {
            this.outputSnapshots = outputSnapshots;
            return this;
        }

        public Builder statistics(ExecutionStatistics statistics) {
            this.statistics = statistics;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ExecutionHistory build() {
            return new ExecutionHistory(runId, workflowId, workflowVersion, tenantId, created, lastUpdated,
                    events, nodeExecutions, stateTransitions, inputSnapshots, outputSnapshots,
                    statistics, metadata);
        }
    }
}
