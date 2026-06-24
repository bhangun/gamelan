package tech.kayys.gamelan.engine.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.event.CompensationCompletedEvent;
import tech.kayys.gamelan.engine.event.CompensationFailedEvent;
import tech.kayys.gamelan.engine.event.CompensationStartedEvent;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
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
import tech.kayys.gamelan.engine.execution.ExecutionContext;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.SuspensionInfo;
import tech.kayys.gamelan.engine.saga.CompensationErrors;
import tech.kayys.gamelan.engine.saga.CompensationState;
import tech.kayys.gamelan.engine.saga.CompensationStatus;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * ============================================================================
 * WORKFLOW RUN AGGREGATE ROOT
 * ============================================================================
 *
 * The WorkflowRun is the primary aggregate in the workflow domain.
 * It encapsulates all business logic and invariants related to workflow
 * execution.
 *
 * Design Principles:
 * - Aggregates protect invariants
 * - All state changes produce domain events
 * - External systems interact only through public methods
 * - Internal consistency is always maintained
 *
 * State Transitions (State Machine):
 * CREATED -> PENDING -> RUNNING -> {SUSPENDED, COMPLETED, FAILED, CANCELLED}
 * SUSPENDED -> RUNNING
 * RUNNING -> COMPENSATING -> COMPENSATED
 */
public class WorkflowRun {

    // ==================== AGGREGATE IDENTITY ====================
    private final WorkflowRunId id;
    private final TenantId tenantId;
    private final WorkflowDefinitionId definitionId;

    // ==================== WORKFLOW STATE ====================
    private RunStatus status;
    private final ExecutionContext context;
    private final WorkflowDefinition definition;
    private final Map<NodeId, NodeDefinition> nodeDefinitionIndex;

    // ==================== EXECUTION TRACKING ====================
    private final Map<NodeId, NodeExecution> nodeExecutions;
    private final List<String> executionPath; // Ordered list of executed nodes
    private final Queue<NodeId> pendingNodes; // Nodes ready to execute

    // ==================== TEMPORAL TRACKING ====================
    private final Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant lastUpdatedAt;

    // ==================== SUSPENSION & SIGNALS ====================
    private SuspensionInfo suspensionInfo;
    private final Map<String, Signal> pendingSignals;

    // ==================== COMPENSATION ====================
    private CompensationState compensationState;

    // ==================== EVENT SOURCING ====================
    private final List<ExecutionEvent> uncommittedEvents;
    private long version; // Optimistic locking version

    // ==================== CONSTRUCTOR ====================

    private WorkflowRun(
            WorkflowRunId id,
            TenantId tenantId,
            WorkflowDefinition definition,
            Map<String, Object> inputs) {

        this.id = Objects.requireNonNull(id, "WorkflowRunId cannot be null");
        this.tenantId = Objects.requireNonNull(tenantId, "TenantId cannot be null");
        this.definitionId = definition.id();
        this.definition = Objects.requireNonNull(definition, "WorkflowDefinition cannot be null");
        this.nodeDefinitionIndex = indexNodes(this.definition);

        Map<String, Object> normalizedInputs = validateAndNormalizeInputs(inputs);

        this.status = RunStatus.CREATED;
        this.context = new ExecutionContext(id, tenantId, normalizedInputs);

        this.nodeExecutions = new HashMap<>();
        this.executionPath = new ArrayList<>();
        this.pendingNodes = new LinkedList<>();

        this.createdAt = Instant.now();
        this.lastUpdatedAt = this.createdAt;

        this.pendingSignals = new HashMap<>();
        this.uncommittedEvents = new ArrayList<>();
        this.version = 0;

    }

    private WorkflowRun(
            WorkflowRunSnapshot snapshot,
            WorkflowDefinition definition) {

        this.id = Objects.requireNonNull(snapshot.id(), "WorkflowRunId cannot be null");
        this.tenantId = Objects.requireNonNull(snapshot.tenantId(), "TenantId cannot be null");
        this.definition = Objects.requireNonNull(definition, "WorkflowDefinition cannot be null");
        this.definitionId = definition.id();
        this.nodeDefinitionIndex = indexNodes(this.definition);

        if (!Objects.equals(snapshot.definitionId(), definition.id())) {
            throw new GamelanException(
                    ErrorCode.WORKFLOW_INVALID_DEFINITION,
                    "Snapshot definition does not match supplied workflow definition");
        }
        if (!Objects.equals(snapshot.tenantId(), definition.tenantId())) {
            throw new GamelanException(
                    ErrorCode.WORKFLOW_INVALID_DEFINITION,
                    "Snapshot tenant does not match supplied workflow definition");
        }
        if (hasConcreteVersion(snapshot.definitionVersion())
                && !Objects.equals(snapshot.definitionVersion(), definition.version())) {
            throw new GamelanException(
                    ErrorCode.WORKFLOW_INVALID_DEFINITION,
                    "Snapshot workflow version does not match supplied workflow definition");
        }

        Instant safeCreatedAt = snapshot.createdAt() != null ? snapshot.createdAt() : Instant.now();
        this.status = snapshot.status() != null ? snapshot.status() : RunStatus.CREATED;
        this.context = ExecutionContext.builder()
                .runId(snapshot.id())
                .tenantId(snapshot.tenantId())
                .variables(snapshot.variables() != null ? snapshot.variables() : Map.of())
                .createdAt(safeCreatedAt)
                .startedAt(snapshot.startedAt())
                .completedAt(snapshot.completedAt())
                .lastUpdatedAt(snapshot.completedAt() != null ? snapshot.completedAt() : safeCreatedAt)
                .build();

        this.nodeExecutions = new HashMap<>();
        if (snapshot.nodeExecutions() != null) {
            snapshot.nodeExecutions().forEach((nodeId, execution) -> this.nodeExecutions.put(
                    nodeId,
                    copyNodeExecution(nodeId, execution, nodeDefinitionIndex)));
        }

        this.executionPath = new ArrayList<>(
                snapshot.executionPath() != null ? snapshot.executionPath() : List.of());
        this.pendingNodes = new LinkedList<>();
        this.nodeExecutions.forEach((nodeId, execution) -> {
            if (execution.getStatus() == NodeExecutionStatus.PENDING || execution.canRetry()) {
                this.pendingNodes.offer(nodeId);
            }
        });

        this.createdAt = safeCreatedAt;
        this.startedAt = snapshot.startedAt();
        this.completedAt = snapshot.completedAt();
        this.lastUpdatedAt = snapshot.completedAt() != null ? snapshot.completedAt() : safeCreatedAt;

        this.suspensionInfo = snapshot.suspensionInfo();
        this.pendingSignals = new HashMap<>(
                snapshot.pendingSignals() != null ? snapshot.pendingSignals() : Map.of());
        this.compensationState = snapshot.compensationState();
        this.uncommittedEvents = new ArrayList<>();
        this.version = Math.max(0, snapshot.version());
    }

    // ==================== FACTORY METHODS ====================

    /**
     * Create a new workflow run
     */
    public static WorkflowRun create(
            TenantId tenantId,
            WorkflowDefinition definition,
            Map<String, Object> inputs) {

        validateRunnableDefinition(definition, "Cannot create run for invalid workflow definition");
        WorkflowRunId runId = WorkflowRunId.generate();
        WorkflowRun run = new WorkflowRun(runId, tenantId, definition, inputs);

        // Raise domain event
        run.raiseEvent(new WorkflowStartedEvent(
                UUID.randomUUID().toString(),
                runId,
                definition.id(),
                tenantId,
                definition.version(),
                new HashMap<>(run.context.getVariables()),
                Instant.now()));

        return run;
    }

    /**
     * Restore a persisted workflow snapshot without producing new domain events.
     */
    public static WorkflowRun restore(
            WorkflowRunSnapshot snapshot,
            WorkflowDefinition definition) {
        Objects.requireNonNull(snapshot, "WorkflowRunSnapshot cannot be null");
        return new WorkflowRun(snapshot, definition);
    }

    // ==================== GETTERS ====================

    public WorkflowRunId id() {
        return id;
    }

    public RunStatus status() {
        return status;
    }

    /**
     * Reconstitute from event stream (Event Sourcing)
     */
    public static WorkflowRun fromEvents(
            WorkflowRunId id,
            TenantId tenantId,
            WorkflowDefinition definition,
            List<ExecutionEvent> events) {

        Objects.requireNonNull(id, "WorkflowRunId cannot be null");
        Objects.requireNonNull(tenantId, "TenantId cannot be null");
        Objects.requireNonNull(definition, "WorkflowDefinition cannot be null");
        List<ExecutionEvent> safeEvents = events != null ? events : List.of();

        WorkflowStartedEvent creationEvent = safeEvents.stream()
                .filter(e -> e instanceof WorkflowStartedEvent)
                .map(e -> (WorkflowStartedEvent) e)
                .findFirst()
                .orElseThrow(() -> new GamelanException(
                        ErrorCode.WORKFLOW_INVALID_DEFINITION,
                        "No WorkflowStartedEvent found"));
        validateReplayEnvelope(id, tenantId, definition, creationEvent, safeEvents);

        WorkflowRun run = new WorkflowRun(id, tenantId, definition, creationEvent.inputs());

        safeEvents.forEach(run::apply);
        run.version = safeEvents.size();

        return run;
    }

    private static void validateReplayEnvelope(
            WorkflowRunId id,
            TenantId tenantId,
            WorkflowDefinition definition,
            WorkflowStartedEvent creationEvent,
            List<ExecutionEvent> events) {
        validateEventRunId(id, creationEvent);
        if (!tenantId.equals(creationEvent.tenantId())) {
            throw replayEnvelopeError("Event stream tenant mismatch: expected "
                    + tenantId.value() + " but found " + valueOf(creationEvent.tenantId()));
        }
        if (!definition.id().equals(creationEvent.definitionId())) {
            throw replayEnvelopeError("Event stream workflow definition mismatch: expected "
                    + definition.id().value() + " but found " + valueOf(creationEvent.definitionId()));
        }
        if (hasConcreteVersion(creationEvent.workflowVersion())
                && !Objects.equals(definition.version(), creationEvent.workflowVersion())) {
            throw replayEnvelopeError("Event stream workflow version mismatch: expected "
                    + definition.version() + " but found " + creationEvent.workflowVersion());
        }
        events.forEach(event -> validateEventRunId(id, event));
    }

    private static void validateEventRunId(WorkflowRunId id, ExecutionEvent event) {
        if (event == null) {
            throw replayEnvelopeError("Event stream contains null event");
        }
        if (event.runId() == null) {
            throw replayEnvelopeError("Event stream contains event without run id: " + event.eventType());
        }
        if (!id.equals(event.runId())) {
            throw replayEnvelopeError("Event stream contains event for another run: expected "
                    + id.value() + " but found " + event.runId().value());
        }
    }

    private static GamelanException replayEnvelopeError(String message) {
        return new GamelanException(ErrorCode.WORKFLOW_INVALID_DEFINITION, message);
    }

    private static String valueOf(TenantId tenantId) {
        return tenantId != null ? tenantId.value() : "null";
    }

    private static String valueOf(WorkflowDefinitionId definitionId) {
        return definitionId != null ? definitionId.value() : "null";
    }

    // ==================== COMMAND HANDLERS ====================

    /**
     * Start the workflow execution
     */
    public void start() {
        if (status == RunStatus.RUNNING) {
            return;
        }

        if (status != RunStatus.PENDING) {
            validateTransition(RunStatus.PENDING);
        }
        validateStartableDefinition();

        if (status != RunStatus.PENDING) {
            this.status = RunStatus.PENDING;
            this.startedAt = Instant.now();
            updateTimestamp();
        } else if (startedAt == null) {
            this.startedAt = Instant.now();
            updateTimestamp();
        }

        definition.getStartNodes().forEach(node -> {
            scheduleNode(node.id());
        });

        if (!pendingNodes.isEmpty()) {
            this.status = RunStatus.RUNNING;
            updateTimestamp();
        }
    }

    private void validateStartableDefinition() {
        validateRunnableDefinition(definition, "Cannot start invalid workflow definition");
    }

    private static void validateRunnableDefinition(WorkflowDefinition definition, String messagePrefix) {
        var validation = definition.validate();
        if (validation.isValid()) {
            return;
        }
        throw new GamelanException(
                ErrorCode.WORKFLOW_INVALID_DEFINITION,
                messagePrefix + ": " + String.join("; ", validation.errors()));
    }

    /**
     * Schedule a node for execution
     */
    public NodeExecution scheduleNode(NodeId nodeId) {
        if (status != RunStatus.RUNNING && status != RunStatus.PENDING) {
            throw new GamelanException(
                    ErrorCode.RUN_INVALID_STATE,
                    "Cannot schedule nodes when status is " + status);
        }

        NodeDefinition nodeDef = nodeDefinition(nodeId);

        // Check dependencies are met
        if (!areDependenciesMet(nodeDef)) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "Dependencies not met for node: " + nodeId.value());
        }

        NodeExecution existing = nodeExecutions.get(nodeId);
        if (existing != null) {
            if (existing.getStatus() == NodeExecutionStatus.PENDING || existing.canRetry()) {
                enqueuePending(nodeId);
            }
            return existing;
        }

        // Create node execution
        NodeExecution execution = NodeExecution.create(nodeId, nodeDef);
        nodeExecutions.put(nodeId, execution);
        enqueuePending(nodeId);

        updateTimestamp();

        raiseEvent(new NodeScheduledEvent(
                UUID.randomUUID().toString(),
                id,
                nodeId,
                execution.getAttempt(),
                Instant.now()));

        return execution;
    }

    /**
     * Mark node as started (called by executor)
     */
    public void startNode(NodeId nodeId, int attempt) {
        NodeExecution execution = getNodeExecution(nodeId);
        if (execution.getAttempt() != attempt) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "Attempt mismatch: expected " + execution.getAttempt() +
                            " but got " + attempt);
        }

        if (execution.getStatus() == NodeExecutionStatus.RUNNING ||
                execution.getStatus() == NodeExecutionStatus.EXECUTING ||
                execution.getStatus().isTerminal()) {
            removePending(nodeId);
            return;
        }

        execution.start(attempt);

        removePending(nodeId);
        updateTimestamp();

        raiseEvent(new NodeStartedEvent(
                UUID.randomUUID().toString(),
                id,
                nodeId,
                attempt,
                Instant.now()));
    }

    /**
     * Claim a ready node before dispatch so repeated drive cycles do not deliver
     * the same work more than once when called under the repository lock.
     */
    public Optional<NodeExecution> reserveNodeForDispatch(NodeId nodeId) {
        return reserveNodeForDispatch(nodeId, Instant.now());
    }

    public Optional<NodeExecution> reserveNodeForDispatch(NodeId nodeId, Instant now) {
        NodeExecution execution = nodeExecutions.get(nodeId);
        if (execution == null) {
            execution = scheduleNode(nodeId);
        }

        if (execution.canRetry() && !execution.isRetryDue(now)) {
            return Optional.empty();
        }

        if (execution.getStatus() != NodeExecutionStatus.PENDING && !execution.canRetry()) {
            removePending(nodeId);
            return Optional.empty();
        }

        startNode(nodeId, execution.getAttempt());
        return Optional.of(execution);
    }

    /**
     * Handle node completion
     */
    public void completeNode(NodeId nodeId, int attempt, Map<String, Object> output) {
        NodeExecution execution = getNodeExecution(nodeId);

        if (execution.getAttempt() != attempt) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "Attempt mismatch: expected " + execution.getAttempt() +
                            " but got " + attempt);
        }

        if (execution.isCompleted() || execution.isFailed()) {
            removePending(nodeId);
            return;
        }

        Map<String, Object> safeOutput = output != null ? output : Map.of();
        execution.complete(safeOutput);
        removePending(nodeId);
        addExecutionPath(nodeId.value());

        // Store output in context
        safeOutput.forEach((key, value) -> context.setVariable(nodeId.value() + "." + key, value));

        updateTimestamp();

        raiseEvent(new NodeCompletedEvent(
                UUID.randomUUID().toString(),
                id,
                nodeId,
                attempt,
                safeOutput,
                Instant.now()));

        // Evaluate next steps
        evaluateWorkflowProgress();
    }

    /**
     * Handle node failure
     */
    public void failNode(NodeId nodeId, int attempt, ErrorInfo error) {
        NodeExecution execution = getNodeExecution(nodeId);
        ErrorInfo safeError = error != null
                ? error
                : new ErrorInfo("UNKNOWN_ERROR", "Node failed without error details", "", Map.of());

        if (execution.getAttempt() != attempt) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "Attempt mismatch: expected " + execution.getAttempt() +
                            " but got " + attempt);
        }

        if (execution.isCompleted() || execution.isFailed()) {
            removePending(nodeId);
            return;
        }

        NodeDefinition nodeDef = nodeDefinition(nodeId);

        RetryPolicy retryPolicy = nodeDef.retryPolicy() != null ? nodeDef.retryPolicy()
                : definition.defaultRetryPolicy();

        boolean willRetry = retryPolicy.shouldRetry(attempt);
        Instant occurredAt = Instant.now();
        Instant retryAt = willRetry ? retryAtFor(retryPolicy, attempt, occurredAt) : null;

        if (willRetry) {
            execution.scheduleRetry(safeError, retryAt);
            enqueuePending(nodeId); // Re-queue for retry
        } else {
            execution.fail(safeError);
            removePending(nodeId);
            addExecutionPath(nodeId.value() + ":FAILED");
        }

        updateTimestamp();

        raiseEvent(new NodeFailedEvent(
                UUID.randomUUID().toString(),
                id,
                nodeId,
                attempt,
                safeError,
                willRetry,
                occurredAt,
                retryAt));

        // Check if critical node failure should fail workflow
        if (!willRetry && nodeDef.isCritical()) {
            fail(new ErrorInfo(
                    "CRITICAL_NODE_FAILED",
                    "Critical node " + nodeId.value() + " failed",
                    safeError.stackTrace(),
                    Map.of("nodeId", nodeId.value())));
        } else if (!willRetry) {
            evaluateWorkflowProgress();
        }
    }

    private Instant retryAtFor(RetryPolicy retryPolicy, int failedAttempt, Instant baseTime) {
        Duration delay = retryPolicy.calculateDelay(failedAttempt);
        Instant effectiveBase = baseTime != null ? baseTime : Instant.now();
        return delay.isZero() || delay.isNegative() ? effectiveBase : effectiveBase.plus(delay);
    }

    private Instant retryAtFor(NodeId nodeId, int failedAttempt, Instant baseTime) {
        NodeDefinition nodeDef = nodeDefinition(nodeId);
        RetryPolicy retryPolicy = nodeDef.retryPolicy() != null ? nodeDef.retryPolicy()
                : definition.defaultRetryPolicy();
        return retryAtFor(retryPolicy, failedAttempt, baseTime);
    }

    /**
     * Suspend the workflow (for human tasks, external signals, etc.)
     */
    public void suspend(String reason, NodeId waitingOnNodeId) {
        validateTransition(RunStatus.SUSPENDED);

        this.status = RunStatus.SUSPENDED;
        this.suspensionInfo = new SuspensionInfo(reason, waitingOnNodeId, Instant.now());
        updateTimestamp();

        raiseEvent(new WorkflowSuspendedEvent(
                UUID.randomUUID().toString(),
                id,
                reason,
                waitingOnNodeId,
                Instant.now()));

        Signal pendingSignal = removePendingSignalFor(waitingOnNodeId);
        if (pendingSignal != null) {
            resume(pendingSignal.payload(), null);
        }
    }

    /**
     * Resume the workflow
     */
    public void resume(Map<String, Object> resumeData, String humanTaskId) {
        if (status != RunStatus.SUSPENDED) {
            throw new GamelanException(
                    ErrorCode.RUN_INVALID_STATE,
                    "Cannot resume workflow in status: " + status);
        }

        Map<String, Object> safeResumeData = resumeData != null ? resumeData : Map.of();

        // Merge resume data into context
        safeResumeData.forEach(context::setVariable);

        this.status = RunStatus.RUNNING;
        this.suspensionInfo = null;
        updateTimestamp();

        raiseEvent(new WorkflowResumedEvent(
                UUID.randomUUID().toString(),
                id,
                safeResumeData,
                humanTaskId,
                Instant.now()));

        evaluateWorkflowProgress();
    }

    /**
     * Receive external signal
     */
    public void signal(Signal signal) {
        applySignal(signal);
    }

    public boolean applySignal(Signal signal) {
        Objects.requireNonNull(signal, "Signal cannot be null");

        if (status != RunStatus.SUSPENDED) {
            if (signalPayloadAlreadyApplied(signal)) {
                return false;
            }
            // Buffer signal for later processing
            Signal previous = pendingSignals.put(signalKey(signal), signal);
            return !Objects.equals(previous, signal);
        }

        if (suspensionInfo != null && matchesSuspension(signal, suspensionInfo.waitingOnNodeId())) {
            resume(signal.payload(), null);
            return true;
        } else {
            Signal previous = pendingSignals.put(signalKey(signal), signal);
            return !Objects.equals(previous, signal);
        }
    }

    private Signal removePendingSignalFor(NodeId waitingOnNodeId) {
        String matchingKey = pendingSignals.entrySet().stream()
                .filter(entry -> matchesSuspension(entry.getValue(), waitingOnNodeId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        return matchingKey != null ? pendingSignals.remove(matchingKey) : null;
    }

    private boolean matchesSuspension(Signal signal, NodeId waitingOnNodeId) {
        return signal.targetNodeId() == null || Objects.equals(signal.targetNodeId(), waitingOnNodeId);
    }

    private String signalKey(Signal signal) {
        String target = signal.targetNodeId() != null ? signal.targetNodeId().value() : "";
        return Objects.toString(signal.name(), "") + "@" + target;
    }

    private boolean signalPayloadAlreadyApplied(Signal signal) {
        Map<String, Object> payload = signal.payload();
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        return payload.entrySet().stream()
                .allMatch(entry -> Objects.equals(context.getVariable(entry.getKey()), entry.getValue()));
    }

    /**
     * Complete the entire workflow
     */
    public void complete(Map<String, Object> outputs) {
        validateTransition(RunStatus.COMPLETED);

        this.status = RunStatus.COMPLETED;
        this.completedAt = Instant.now();
        updateTimestamp();

        raiseEvent(new WorkflowCompletedEvent(
                UUID.randomUUID().toString(),
                id,
                outputs,
                Instant.now()));
    }

    /**
     * Fail the entire workflow
     */
    public void fail(ErrorInfo error) {
        validateTransition(RunStatus.FAILED);

        this.status = RunStatus.FAILED;
        this.completedAt = Instant.now();
        updateTimestamp();

        raiseEvent(new WorkflowFailedEvent(
                UUID.randomUUID().toString(),
                id,
                error,
                Instant.now()));

        // Check if compensation is needed
        if (definition.isCompensationEnabled()) {
            initiateCompensation();
        }
    }

    /**
     * Cancel the workflow
     */
    public void cancel(String reason) {
        if (status.isTerminal()) {
            throw new GamelanException(
                    ErrorCode.RUN_ALREADY_TERMINAL,
                    "Cannot cancel workflow in terminal status: " + status);
        }
        validateTransition(RunStatus.CANCELLED);

        this.status = RunStatus.CANCELLED;
        this.completedAt = Instant.now();
        updateTimestamp();

        raiseEvent(new WorkflowCancelledEvent(
                UUID.randomUUID().toString(),
                id,
                reason,
                Instant.now()));

        // Initiate compensation for already executed nodes
        if (definition.isCompensationEnabled()) {
            initiateCompensation();
        }
    }

    // ==================== BUSINESS LOGIC ====================

    /**
     * Evaluate workflow progress and schedule next nodes
     */
    private void evaluateWorkflowProgress() {
        if (status != RunStatus.RUNNING) {
            return;
        }

        boolean allNodesResolved = definition.nodes().stream()
                .allMatch(this::isNodeResolved);

        if (allNodesResolved) {
            Map<String, Object> outputs = collectOutputs();
            complete(outputs);
            return;
        }

        // Find nodes ready to execute
        List<NodeDefinition> readyNodes = definition.nodes().stream()
                .filter(node -> !nodeExecutions.containsKey(node.id()) ||
                        nodeExecutions.get(node.id()).canRetry())
                .filter(this::areDependenciesMet)
                .toList();

        // Schedule ready nodes
        readyNodes.forEach(node -> {
            if (!pendingNodes.contains(node.id())) {
                scheduleNode(node.id());
            }
        });

        if (pendingNodes.isEmpty() && !hasActiveNodeExecutions()) {
            fail(new ErrorInfo(
                    "WORKFLOW_STUCK",
                    "Workflow cannot progress; unresolved nodes remain blocked",
                    "",
                    Map.of(
                            "unresolvedNodes", unresolvedNodeIds(),
                            "failedNodes", failedNodeIds())));
        }
    }

    private boolean isNodeResolved(NodeDefinition node) {
        NodeExecution exec = nodeExecutions.get(node.id());
        if (exec == null) {
            return false;
        }
        return exec.isCompleted() || (exec.isFailed() && !node.isCritical());
    }

    private boolean hasActiveNodeExecutions() {
        return nodeExecutions.values().stream()
                .map(NodeExecution::getStatus)
                .anyMatch(status -> status == NodeExecutionStatus.PENDING
                        || status == NodeExecutionStatus.RUNNING
                        || status == NodeExecutionStatus.EXECUTING
                        || status == NodeExecutionStatus.WAITING
                        || status == NodeExecutionStatus.RETRYING);
    }

    private List<String> unresolvedNodeIds() {
        return definition.nodes().stream()
                .filter(node -> !isNodeResolved(node))
                .map(node -> node.id().value())
                .toList();
    }

    private List<String> failedNodeIds() {
        return nodeExecutions.values().stream()
                .filter(NodeExecution::isFailed)
                .map(execution -> execution.getNodeId().value())
                .toList();
    }

    /**
     * Check if all dependencies for a node are met
     */
    private boolean areDependenciesMet(NodeDefinition node) {
        return node.dependsOn().stream()
                .allMatch(depId -> {
                    NodeExecution depExec = nodeExecutions.get(depId);
                    return depExec != null && depExec.isCompleted();
                });
    }

    /**
     * Collect workflow outputs
     */
    private Map<String, Object> collectOutputs() {
        Map<String, Object> outputs = new HashMap<>();

        definition.outputs().forEach((name, outputDef) -> {
            Object value = context.getVariable(name);
            if (value != null) {
                outputs.put(name, value);
            }
        });

        return outputs;
    }

    /**
     * Initiate compensation for executed nodes
     */
    public void initiateCompensation() {
        if (status != RunStatus.FAILED && status != RunStatus.CANCELLED) {
            throw new GamelanException(
                    ErrorCode.RUN_INVALID_STATE,
                    "Compensation can only be initiated from FAILED or CANCELLED state, current status: " + status);
        }

        this.status = RunStatus.COMPENSATING;
        this.completedAt = null;
        this.compensationState = CompensationState.create(
                getCompletedNodes());

        // Raise compensation started event
        raiseEvent(new CompensationStartedEvent(
                UUID.randomUUID().toString(),
                id,
                tenantId,
                getCompletedNodes(),
                Instant.now()));
    }

    /**
     * Mark a node as compensated during the compensation process
     */
    public void compensateNode(NodeId nodeId) {
        if (status != RunStatus.COMPENSATING) {
            throw new GamelanException(
                    ErrorCode.RUN_INVALID_STATE,
                    "Cannot compensate node when workflow is not compensating. Current status: " + status);
        }

        if (compensationState == null) {
            throw new GamelanException(
                    ErrorCode.RUN_COMPENSATION_NOT_READY,
                    "Compensation state is not initialized");
        }

        // Update compensation state
        this.compensationState = compensationState.markNodeCompensated(nodeId);
        updateTimestamp();
    }

    /**
     * Claim a node before executing compensation side effects.
     */
    public void claimCompensationNode(NodeId nodeId, String claimId, Instant now, Duration lease) {
        if (status != RunStatus.COMPENSATING) {
            throw new GamelanException(
                    ErrorCode.RUN_INVALID_STATE,
                    "Cannot claim compensation node when workflow is not compensating. Current status: " + status);
        }

        if (compensationState == null) {
            throw new GamelanException(
                    ErrorCode.RUN_COMPENSATION_NOT_READY,
                    "Compensation state is not initialized");
        }

        this.compensationState = compensationState.claimNode(nodeId, claimId, now, lease);
        updateTimestamp();
    }

    /**
     * Release a claim when rollback execution fails before durable completion.
     */
    public void releaseCompensationNodeClaim(NodeId nodeId, String claimId) {
        if (status != RunStatus.COMPENSATING || compensationState == null) {
            return;
        }

        this.compensationState = compensationState.releaseNodeClaim(nodeId, claimId);
        updateTimestamp();
    }

    /**
     * Mark compensation as failed
     */
    public void failCompensation(ErrorInfo error) {
        if (status != RunStatus.COMPENSATING) {
            throw new GamelanException(
                    ErrorCode.RUN_INVALID_STATE,
                    "Cannot fail compensation when workflow is not compensating. Current status: " + status);
        }

        if (compensationState == null) {
            throw new GamelanException(
                    ErrorCode.RUN_COMPENSATION_NOT_READY,
                    "Compensation state is not initialized");
        }

        ErrorInfo safeError = CompensationErrors.normalizeFailure(error);
        this.compensationState = compensationState.markFailed();
        this.status = RunStatus.FAILED;
        this.completedAt = Instant.now();
        updateTimestamp();

        raiseEvent(new CompensationFailedEvent(
                UUID.randomUUID().toString(),
                id,
                tenantId,
                safeError,
                Instant.now()));
    }

    /**
     * Complete the compensation process
     */
    public void completeCompensation() {
        validateTransition(RunStatus.COMPENSATED);
        if (compensationState == null) {
            throw new GamelanException(
                    ErrorCode.RUN_COMPENSATION_NOT_READY,
                    "Compensation state is not initialized");
        }

        this.compensationState = compensationState.markCompleted();
        this.status = RunStatus.COMPENSATED;
        this.completedAt = Instant.now();
        updateTimestamp();

        // Raise compensation completed event
        raiseEvent(new CompensationCompletedEvent(
                UUID.randomUUID().toString(),
                id,
                tenantId,
                this.compensationState.compensatedNodes(),
                Instant.now()));
    }

    /**
     * Get the current compensation state
     */
    public CompensationState getCompensationState() {
        return compensationState;
    }

    /**
     * Check if compensation is in progress
     */
    public boolean isCompensating() {
        return status == RunStatus.COMPENSATING;
    }

    /**
     * Check if compensation is complete
     */
    public boolean isCompensated() {
        return status == RunStatus.COMPENSATED;
    }

    /**
     * Get the next node that needs to be compensated
     */
    public NodeId getNextNodeToCompensate() {
        if (compensationState == null) {
            return null;
        }
        return compensationState.getNextNodeToCompensate();
    }

    /**
     * Get list of successfully completed nodes
     */
    private List<NodeId> getCompletedNodes() {
        Set<NodeId> completed = new HashSet<>();
        nodeExecutions.forEach((nodeId, execution) -> {
            if (execution.isCompleted()) {
                completed.add(nodeId);
            }
        });

        List<NodeId> executionOrderedNodes = executionPath.stream()
                .filter(pathEntry -> pathEntry != null && !pathEntry.contains(":"))
                .map(NodeId::of)
                .filter(completed::contains)
                .distinct()
                .toList();

        List<NodeId> compensationOrder = new ArrayList<>(executionOrderedNodes);
        if (compensationOrder.isEmpty()) {
            compensationOrder = new ArrayList<>(completed);
        }

        Collections.reverse(compensationOrder);
        return compensationOrder;
    }

    private static NodeExecution copyNodeExecution(
            NodeId nodeId,
            NodeExecution source,
            Map<NodeId, NodeDefinition> nodeDefinitionIndex) {
        Objects.requireNonNull(source, "NodeExecution cannot be null");

        NodeDefinition nodeDef = nodeDefinition(nodeId, nodeDefinitionIndex);

        NodeExecution copy = NodeExecution.create(nodeId, nodeDef);
        copy.setStatus(source.getStatus());
        copy.setAttempt(source.getAttempt());
        copy.setStartedAt(source.getStartedAt());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setRetryAt(source.getRetryAt());
        copy.setOutput(source.getOutput());
        copy.setLastError(source.getLastError());
        return copy;
    }

    // ==================== VALIDATION ====================

    private Map<String, Object> validateAndNormalizeInputs(Map<String, Object> inputs) {
        Map<String, Object> normalizedInputs = new HashMap<>(inputs != null ? inputs : Map.of());

        definition.inputs().forEach((name, inputDef) -> {
            if (inputDef.required() && !normalizedInputs.containsKey(name)) {
                if (inputDef.defaultValue() != null) {
                    normalizedInputs.put(name, inputDef.defaultValue());
                } else {
                    throw new GamelanException(
                            ErrorCode.MISSING_REQUIRED_FIELD,
                            "Required input missing: " + name);
                }
            }
        });

        return normalizedInputs;
    }

    private void validateTransition(RunStatus targetStatus) {
        if (!status.canTransitionTo(targetStatus)) {
            throw new GamelanException(
                    ErrorCode.RUN_INVALID_STATE,
                    String.format("Invalid state transition from %s to %s",
                            status, targetStatus));
        }
    }

    // ==================== EVENT SOURCING ====================

    private void raiseEvent(ExecutionEvent event) {
        uncommittedEvents.add(event);
        context.recordEvent(event);
    }

    private void apply(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent workflowStarted) {
            context.setVariables(workflowStarted.inputs());
            lastUpdatedAt = workflowStarted.occurredAt();
        } else if (event instanceof NodeScheduledEvent scheduled) {
            NodeExecution execution = replayNodeExecution(scheduled.nodeId());
            execution.setAttempt(scheduled.attempt());
            execution.setStatus(NodeExecutionStatus.PENDING);
            enqueuePending(scheduled.nodeId());
            startedAt = startedAt != null ? startedAt : scheduled.occurredAt();
            status = RunStatus.RUNNING;
            lastUpdatedAt = scheduled.occurredAt();
        } else if (event instanceof NodeStartedEvent nodeStarted) {
            NodeExecution execution = replayNodeExecution(nodeStarted.nodeId());
            execution.start(nodeStarted.attempt());
            pendingNodes.remove(nodeStarted.nodeId());
            status = RunStatus.RUNNING;
            lastUpdatedAt = nodeStarted.occurredAt();
        } else if (event instanceof NodeCompletedEvent completed) {
            NodeExecution execution = replayNodeExecution(completed.nodeId());
            execution.setAttempt(completed.attempt());
            Map<String, Object> safeOutput = completed.output() != null ? completed.output() : Map.of();
            execution.complete(safeOutput);
            addExecutionPath(completed.nodeId().value());
            safeOutput.forEach((key, value) -> context.setVariable(completed.nodeId().value() + "." + key, value));
            lastUpdatedAt = completed.occurredAt();
        } else if (event instanceof NodeFailedEvent failed) {
            NodeExecution execution = replayNodeExecution(failed.nodeId());
            execution.setAttempt(failed.attempt());
            ErrorInfo safeError = failed.error() != null
                    ? failed.error()
                    : new ErrorInfo("UNKNOWN_ERROR", "Node failed without error details", "", Map.of());
            if (failed.willRetry()) {
                Instant retryAt = failed.retryAt() != null
                        ? failed.retryAt()
                        : retryAtFor(failed.nodeId(), failed.attempt(), failed.occurredAt());
                execution.scheduleRetry(safeError, retryAt);
                enqueuePending(failed.nodeId());
            } else {
                execution.fail(safeError);
                addExecutionPath(failed.nodeId().value() + ":FAILED");
            }
            lastUpdatedAt = failed.occurredAt();
        } else if (event instanceof WorkflowSuspendedEvent suspended) {
            status = RunStatus.SUSPENDED;
            suspensionInfo = new SuspensionInfo(suspended.reason(), suspended.waitingOnNodeId(), suspended.occurredAt());
            lastUpdatedAt = suspended.occurredAt();
        } else if (event instanceof WorkflowResumedEvent resumed) {
            Map<String, Object> resumeData = resumed.resumeData() != null ? resumed.resumeData() : Map.of();
            resumeData.forEach(context::setVariable);
            status = RunStatus.RUNNING;
            suspensionInfo = null;
            lastUpdatedAt = resumed.occurredAt();
        } else if (event instanceof WorkflowCompletedEvent completed) {
            status = RunStatus.COMPLETED;
            completedAt = completed.occurredAt();
            lastUpdatedAt = completed.occurredAt();
        } else if (event instanceof WorkflowFailedEvent failed) {
            status = RunStatus.FAILED;
            completedAt = failed.occurredAt();
            lastUpdatedAt = failed.occurredAt();
        } else if (event instanceof WorkflowCancelledEvent cancelled) {
            status = RunStatus.CANCELLED;
            completedAt = cancelled.occurredAt();
            lastUpdatedAt = cancelled.occurredAt();
        } else if (event instanceof CompensationStartedEvent compensationStarted) {
            status = RunStatus.COMPENSATING;
            completedAt = null;
            compensationState = new CompensationState(
                    new ArrayList<>(compensationStarted.nodesToCompensate()),
                    new ArrayList<>(),
                    compensationStarted.occurredAt(),
                    null,
                    CompensationStatus.PENDING);
            lastUpdatedAt = compensationStarted.occurredAt();
        } else if (event instanceof CompensationCompletedEvent completed) {
            status = RunStatus.COMPENSATED;
            completedAt = completed.occurredAt();
            compensationState = new CompensationState(
                    List.of(),
                    new ArrayList<>(completed.compensatedNodes()),
                    compensationState != null ? compensationState.startedAt() : completed.occurredAt(),
                    completed.occurredAt(),
                    CompensationStatus.COMPLETED);
            lastUpdatedAt = completed.occurredAt();
        } else if (event instanceof CompensationFailedEvent failed) {
            status = RunStatus.FAILED;
            completedAt = failed.occurredAt();
            compensationState = compensationState != null
                    ? new CompensationState(
                            compensationState.nodesToCompensate(),
                            compensationState.compensatedNodes(),
                            compensationState.startedAt(),
                            failed.occurredAt(),
                            CompensationStatus.FAILED)
                    : new CompensationState(List.of(), List.of(), failed.occurredAt(), failed.occurredAt(),
                            CompensationStatus.FAILED);
            lastUpdatedAt = failed.occurredAt();
        }

        context.recordEvent(event);
    }

    private NodeExecution replayNodeExecution(NodeId nodeId) {
        return nodeExecutions.computeIfAbsent(nodeId, id -> {
            NodeDefinition nodeDef = nodeDefinition(id);
            return NodeExecution.create(id, nodeDef);
        });
    }

    private NodeDefinition nodeDefinition(NodeId nodeId) {
        return nodeDefinition(nodeId, nodeDefinitionIndex);
    }

    private static NodeDefinition nodeDefinition(
            NodeId nodeId,
            Map<NodeId, NodeDefinition> nodeDefinitionIndex) {
        NodeDefinition nodeDefinition = nodeDefinitionIndex.get(nodeId);
        if (nodeDefinition == null) {
            throw new GamelanException(
                    ErrorCode.TASK_NOT_FOUND,
                    "Node not found: " + nodeId.value());
        }
        return nodeDefinition;
    }

    private static Map<NodeId, NodeDefinition> indexNodes(WorkflowDefinition definition) {
        Map<NodeId, NodeDefinition> index = new HashMap<>();
        for (NodeDefinition node : definition.nodes()) {
            index.putIfAbsent(node.id(), node);
        }
        return Map.copyOf(index);
    }

    private static boolean hasConcreteVersion(String version) {
        return version != null && !version.isBlank() && !"unknown".equals(version);
    }

    private void enqueuePending(NodeId nodeId) {
        if (!pendingNodes.contains(nodeId)) {
            pendingNodes.offer(nodeId);
        }
    }

    private void removePending(NodeId nodeId) {
        pendingNodes.removeIf(nodeId::equals);
    }

    private void addExecutionPath(String pathEntry) {
        if (!executionPath.contains(pathEntry)) {
            executionPath.add(pathEntry);
        }
    }

    public List<ExecutionEvent> getUncommittedEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    public void markEventsAsCommitted() {
        version += uncommittedEvents.size();
        uncommittedEvents.clear();
    }

    public void markEventsAsCommitted(List<ExecutionEvent> committedEvents) {
        if (committedEvents == null || committedEvents.isEmpty()) {
            return;
        }

        int committed = 0;
        for (ExecutionEvent event : committedEvents) {
            if (uncommittedEvents.remove(event)) {
                committed++;
            }
        }
        version += committed;
    }

    // ==================== GETTERS ====================

    public WorkflowRunId getId() {
        return id;
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    public WorkflowDefinitionId getDefinitionId() {
        return definitionId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public ExecutionContext getContext() {
        return context;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<NodeId> getPendingNodes() {
        return new ArrayList<>(pendingNodes);
    }

    public List<String> getExecutionPath() {
        return List.copyOf(executionPath);
    }

    public NodeExecution getNodeExecution(NodeId nodeId) {
        NodeExecution execution = nodeExecutions.get(nodeId);
        if (execution == null) {
            throw new GamelanException(
                    ErrorCode.TASK_NOT_FOUND,
                    "Node execution not found: " + nodeId.value());
        }
        return execution;
    }

    public Map<NodeId, NodeExecution> getAllNodeExecutions() {
        return Collections.unmodifiableMap(nodeExecutions);
    }

    private void updateTimestamp() {
        this.lastUpdatedAt = Instant.now();
    }

    // ==================== SNAPSHOT ====================

    public WorkflowRunSnapshot createSnapshot() {
        return new WorkflowRunSnapshot(
                id,
                tenantId,
                definitionId,
                definition.version(),
                status,
                new HashMap<>(context.getVariables()),
                new HashMap<>(nodeExecutions),
                new ArrayList<>(executionPath),
                suspensionInfo,
                new HashMap<>(pendingSignals),
                compensationState,
                createdAt,
                startedAt,
                completedAt,
                version);
    }
}
