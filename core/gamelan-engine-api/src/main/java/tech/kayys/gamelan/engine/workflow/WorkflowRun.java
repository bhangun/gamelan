package tech.kayys.gamelan.engine.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;

import tech.kayys.gamelan.engine.error.ErrorInfo;
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
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.SuspensionInfo;
import tech.kayys.gamelan.engine.saga.CompensationState;
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

        this.status = RunStatus.CREATED;
        this.context = new ExecutionContext(id, tenantId, inputs);

        this.nodeExecutions = new HashMap<>();
        this.executionPath = new ArrayList<>();
        this.pendingNodes = new LinkedList<>();

        this.createdAt = Instant.now();
        this.lastUpdatedAt = this.createdAt;

        this.pendingSignals = new HashMap<>();
        this.uncommittedEvents = new ArrayList<>();
        this.version = 0;

        // Validate inputs against definition
        validateInputs(inputs);
    }

    // ==================== FACTORY METHODS ====================

    /**
     * Create a new workflow run
     */
    public static WorkflowRun create(
            TenantId tenantId,
            WorkflowDefinition definition,
            Map<String, Object> inputs) {

        WorkflowRunId runId = WorkflowRunId.generate();
        WorkflowRun run = new WorkflowRun(runId, tenantId, definition, inputs);

        // Raise domain event
        run.raiseEvent(new WorkflowStartedEvent(
                UUID.randomUUID().toString(),
                runId,
                definition.id(),
                tenantId,
                inputs,
                Instant.now()));

        return run;
    }

    /**
     * Reconstitute from event stream (Event Sourcing)
     */
    public static WorkflowRun fromEvents(
            WorkflowRunId id,
            TenantId tenantId,
            WorkflowDefinition definition,
            List<ExecutionEvent> events) {

        // Find the creation event
        var creationEvent = events.stream()
                .filter(e -> e instanceof WorkflowStartedEvent)
                .map(e -> (WorkflowStartedEvent) e)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No WorkflowStartedEvent found"));

        WorkflowRun run = new WorkflowRun(id, tenantId, definition, creationEvent.inputs());

        // Replay all events
        events.forEach(run::apply);

        return run;
    }

    // ==================== COMMAND HANDLERS ====================

    /**
     * Start the workflow execution
     */
    public void start() {
        validateTransition(RunStatus.PENDING);

        this.status = RunStatus.PENDING;
        this.startedAt = Instant.now();
        updateTimestamp();

        // Schedule start nodes
        definition.getStartNodes().forEach(node -> {
            scheduleNode(node.id());
        });

        // Transition to running if nodes were scheduled
        if (!pendingNodes.isEmpty()) {
            this.status = RunStatus.RUNNING;
        }
    }

    /**
     * Schedule a node for execution
     */
    public NodeExecution scheduleNode(NodeId nodeId) {
        if (status != RunStatus.RUNNING && status != RunStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot schedule nodes when status is " + status);
        }

        NodeDefinition nodeDef = definition.findNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Node not found: " + nodeId.value()));

        // Check dependencies are met
        if (!areDependenciesMet(nodeDef)) {
            throw new IllegalStateException(
                    "Dependencies not met for node: " + nodeId.value());
        }

        // Create node execution
        NodeExecution execution = NodeExecution.create(nodeId, nodeDef);
        nodeExecutions.put(nodeId, execution);
        pendingNodes.offer(nodeId);

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
        execution.start(attempt);

        pendingNodes.remove(nodeId);
        updateTimestamp();

        raiseEvent(new NodeStartedEvent(
                UUID.randomUUID().toString(),
                id,
                nodeId,
                attempt,
                Instant.now()));
    }

    /**
     * Handle node completion
     */
    public void completeNode(NodeId nodeId, int attempt, Map<String, Object> output) {
        NodeExecution execution = getNodeExecution(nodeId);

        if (execution.getAttempt() != attempt) {
            throw new IllegalStateException(
                    "Attempt mismatch: expected " + execution.getAttempt() +
                            " but got " + attempt);
        }

        execution.complete(output);
        executionPath.add(nodeId.value());

        // Store output in context
        output.forEach((key, value) -> context.setVariable(nodeId.value() + "." + key, value));

        updateTimestamp();

        raiseEvent(new NodeCompletedEvent(
                UUID.randomUUID().toString(),
                id,
                nodeId,
                attempt,
                output,
                Instant.now()));

        // Evaluate next steps
        evaluateWorkflowProgress();
    }

    /**
     * Handle node failure
     */
    public void failNode(NodeId nodeId, int attempt, ErrorInfo error) {
        NodeExecution execution = getNodeExecution(nodeId);

        NodeDefinition nodeDef = definition.findNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found"));

        RetryPolicy retryPolicy = nodeDef.retryPolicy() != null ? nodeDef.retryPolicy()
                : definition.defaultRetryPolicy();

        boolean willRetry = retryPolicy.shouldRetry(attempt + 1);

        if (willRetry) {
            execution.scheduleRetry(error);
            pendingNodes.offer(nodeId); // Re-queue for retry
        } else {
            execution.fail(error);
            executionPath.add(nodeId.value() + ":FAILED");
        }

        updateTimestamp();

        raiseEvent(new NodeFailedEvent(
                UUID.randomUUID().toString(),
                id,
                nodeId,
                attempt,
                error,
                willRetry,
                Instant.now()));

        // Check if critical node failure should fail workflow
        if (!willRetry && nodeDef.isCritical()) {
            fail(new ErrorInfo(
                    "CRITICAL_NODE_FAILED",
                    "Critical node " + nodeId.value() + " failed",
                    error.stackTrace(),
                    Map.of("nodeId", nodeId.value())));
        } else if (!willRetry) {
            evaluateWorkflowProgress();
        }
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
    }

    /**
     * Resume the workflow
     */
    public void resume(Map<String, Object> resumeData, String humanTaskId) {
        if (status != RunStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot resume workflow in status: " + status);
        }

        // Merge resume data into context
        resumeData.forEach(context::setVariable);

        this.status = RunStatus.RUNNING;
        this.suspensionInfo = null;
        updateTimestamp();

        raiseEvent(new WorkflowResumedEvent(
                UUID.randomUUID().toString(),
                id,
                resumeData,
                humanTaskId,
                Instant.now()));

        evaluateWorkflowProgress();
    }

    /**
     * Receive external signal
     */
    public void signal(Signal signal) {
        if (status != RunStatus.SUSPENDED) {
            // Buffer signal for later processing
            pendingSignals.put(signal.name(), signal);
            return;
        }

        if (suspensionInfo != null &&
                signal.targetNodeId().equals(suspensionInfo.waitingOnNodeId())) {
            resume(signal.payload(), null);
        } else {
            pendingSignals.put(signal.name(), signal);
        }
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
        if (definition.compensationPolicy() != null) {
            initiateCompensation();
        }
    }

    /**
     * Cancel the workflow
     */
    public void cancel(String reason) {
        if (status.isTerminal()) {
            throw new IllegalStateException("Cannot cancel workflow in terminal status: " + status);
        }

        this.status = RunStatus.CANCELLED;
        this.completedAt = Instant.now();
        updateTimestamp();

        raiseEvent(new WorkflowCancelledEvent(
                UUID.randomUUID().toString(),
                id,
                reason,
                Instant.now()));

        // Initiate compensation for already executed nodes
        if (definition.compensationPolicy() != null) {
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

        // Check if all nodes are complete
        boolean allNodesComplete = definition.nodes().stream()
                .allMatch(node -> {
                    NodeExecution exec = nodeExecutions.get(node.id());
                    return exec != null && exec.isCompleted();
                });

        if (allNodesComplete) {
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

        // If no nodes pending and some failed, workflow might be stuck
        if (pendingNodes.isEmpty() && !allNodesComplete) {
            // Check if any critical nodes failed
            boolean hasCriticalFailure = nodeExecutions.values().stream()
                    .anyMatch(exec -> exec.isFailed() &&
                            definition.findNode(exec.getNodeId())
                                    .map(NodeDefinition::isCritical)
                                    .orElse(false));

            if (hasCriticalFailure) {
                fail(new ErrorInfo(
                        "WORKFLOW_STUCK",
                        "Workflow cannot progress due to failed critical nodes",
                        "",
                        Map.of()));
            }
        }
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
            throw new IllegalStateException(
                    "Compensation can only be initiated from FAILED or CANCELLED state, current status: " + status);
        }

        this.status = RunStatus.COMPENSATING;
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
            throw new IllegalStateException(
                    "Cannot compensate node when workflow is not compensating. Current status: " + status);
        }

        if (compensationState == null) {
            throw new IllegalStateException("Compensation state is not initialized");
        }

        // Update compensation state
        this.compensationState = compensationState.markNodeCompensated(nodeId);
        updateTimestamp();

        // Check if compensation is complete
        if (compensationState.isComplete()) {
            completeCompensation();
        }
    }

    /**
     * Mark compensation as failed
     */
    public void failCompensation(ErrorInfo error) {
        if (status != RunStatus.COMPENSATING) {
            throw new IllegalStateException(
                    "Cannot fail compensation when workflow is not compensating. Current status: " + status);
        }

        if (compensationState == null) {
            throw new IllegalStateException("Compensation state is not initialized");
        }

        this.compensationState = compensationState.markFailed();
        this.status = RunStatus.FAILED;
        this.completedAt = Instant.now();
        updateTimestamp();

        raiseEvent(new CompensationFailedEvent(
                UUID.randomUUID().toString(),
                id,
                tenantId,
                error,
                Instant.now()));
    }

    /**
     * Complete the compensation process
     */
    public void completeCompensation() {
        this.status = RunStatus.COMPENSATED;
        this.completedAt = Instant.now();
        updateTimestamp();

        // Raise compensation completed event
        raiseEvent(new CompensationCompletedEvent(
                UUID.randomUUID().toString(),
                id,
                tenantId,
                compensationState != null ? compensationState.compensatedNodes() : List.of(),
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
        return nodeExecutions.entrySet().stream()
                .filter(e -> e.getValue().isCompleted())
                .map(Map.Entry::getKey)
                .toList();
    }

    // ==================== VALIDATION ====================

    private void validateInputs(Map<String, Object> inputs) {
        definition.inputs().forEach((name, inputDef) -> {
            if (inputDef.required() && !inputs.containsKey(name)) {
                if (inputDef.defaultValue() != null) {
                    inputs.put(name, inputDef.defaultValue());
                } else {
                    throw new IllegalArgumentException(
                            "Required input missing: " + name);
                }
            }
        });
    }

    private void validateTransition(RunStatus targetStatus) {
        if (!status.canTransitionTo(targetStatus)) {
            throw new IllegalStateException(
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
        // Apply event to state (used in event replay)
        context.recordEvent(event);
    }

    public List<ExecutionEvent> getUncommittedEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    public void markEventsAsCommitted() {
        uncommittedEvents.clear();
        version++;
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

    public NodeExecution getNodeExecution(NodeId nodeId) {
        NodeExecution execution = nodeExecutions.get(nodeId);
        if (execution == null) {
            throw new IllegalArgumentException("Node execution not found: " + nodeId.value());
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
                status,
                new HashMap<>(context.getVariables()),
                new HashMap<>(nodeExecutions),
                new ArrayList<>(executionPath),
                createdAt,
                startedAt,
                completedAt,
                version);
    }
}
