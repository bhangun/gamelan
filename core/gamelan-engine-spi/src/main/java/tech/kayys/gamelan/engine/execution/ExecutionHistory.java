package tech.kayys.gamelan.engine.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.node.NodeExecutionRecord;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
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
        this.events = events != null ? Collections.unmodifiableList(events) : List.of();
        this.nodeExecutions = nodeExecutions != null ? Collections.unmodifiableList(nodeExecutions) : List.of();
        this.stateTransitions = stateTransitions != null ? Collections.unmodifiableList(stateTransitions) : List.of();
        this.inputSnapshots = inputSnapshots != null ? Collections.unmodifiableMap(inputSnapshots) : Map.of();
        this.outputSnapshots = outputSnapshots != null ? Collections.unmodifiableMap(outputSnapshots) : Map.of();
        this.statistics = statistics != null ? statistics : ExecutionStatistics.empty();
        this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Map.of();
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

        List<ExecutionEventHistory> historyEvents = domainEvents.stream()
                .map(domainEvent -> ExecutionEventHistory.builder()
                        .eventId(domainEvent.eventId())
                        .eventType(mapEventType(domainEvent.eventType()))
                        .timestamp(domainEvent.occurredAt())
                        .source("event-store")
                        .payload(Map.of())
                        .metadata(Map.of("domainEventType", domainEvent.eventType()))
                        .build())
                .toList();

        return ExecutionHistory.builder()
                .runId(runId)
                .workflowId(WorkflowId.of("unknown"))
                .workflowVersion("unknown")
                .tenantId("unknown")
                .created(historyEvents.isEmpty() ? Instant.now() : historyEvents.get(0).getTimestamp())
                .lastUpdated(Instant.now())
                .events(historyEvents)
                .nodeExecutions(new ArrayList<>())
                .stateTransitions(new ArrayList<>())
                .inputSnapshots(new LinkedHashMap<>())
                .outputSnapshots(new LinkedHashMap<>())
                .statistics(ExecutionStatistics.empty())
                .metadata(Map.of("source", "domain-events"))
                .build();
    }

    private static ExecutionEventHistory.ExecutionEventType mapEventType(String eventType) {
        return switch (eventType) {
            case "WorkflowStartedEvent" -> ExecutionEventHistory.ExecutionEventType.RUN_STARTED;
            case "NodeScheduledEvent" -> ExecutionEventHistory.ExecutionEventType.NODE_STARTED;
            case "NodeStartedEvent" -> ExecutionEventHistory.ExecutionEventType.NODE_STARTED;
            case "NodeCompletedEvent" -> ExecutionEventHistory.ExecutionEventType.NODE_COMPLETED;
            case "NodeFailedEvent" -> ExecutionEventHistory.ExecutionEventType.NODE_FAILED;
            case "WorkflowSuspendedEvent" -> ExecutionEventHistory.ExecutionEventType.RUN_WAITING;
            case "WorkflowResumedEvent" -> ExecutionEventHistory.ExecutionEventType.RUN_RESUMED;
            case "WorkflowCompletedEvent" -> ExecutionEventHistory.ExecutionEventType.RUN_COMPLETED;
            case "WorkflowFailedEvent" -> ExecutionEventHistory.ExecutionEventType.RUN_FAILED;
            case "WorkflowCancelledEvent" -> ExecutionEventHistory.ExecutionEventType.RUN_CANCELLED;
            default -> ExecutionEventHistory.ExecutionEventType.STATE_UPDATED;
        };
    }

    public static ExecutionHistory fromSnapshot(
            WorkflowRunSnapshot snapshot,
            List<ExecutionEventHistory> events,
            List<NodeExecutionRecord> nodeExecutions) {

        return ExecutionHistory.builder()
                .runId(snapshot.id())
                .workflowId(WorkflowId.of(snapshot.definitionId().value()))
                .workflowVersion("1.0.0")
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
            NODE_STARTED, NODE_COMPLETED, NODE_FAILED, NODE_WAITING, STATE_UPDATED, SIGNAL_RECEIVED,
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
            this.payload = payload != null ? Collections.unmodifiableMap(payload) : Map.of();
            this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Map.of();
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
            this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Map.of();
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
            this.nodeTypeCounts = nodeTypeCounts != null ? Collections.unmodifiableMap(nodeTypeCounts) : Map.of();
            this.nodeTypeDurations = nodeTypeDurations != null ? Collections.unmodifiableMap(nodeTypeDurations)
                    : Map.of();
            this.metrics = metrics != null ? Collections.unmodifiableMap(metrics) : Map.of();
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
