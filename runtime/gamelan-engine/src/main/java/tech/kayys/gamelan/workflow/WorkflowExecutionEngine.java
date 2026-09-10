package tech.kayys.gamelan.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.gamelan.engine.execution.ExecutionPlan;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.plugin.PluginService;

/**
 * Core execution engine that evaluates workflow progress
 * and determines next steps
 */
@ApplicationScoped
public class WorkflowExecutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExecutionEngine.class);

    @Inject
    WorkflowDefinitionRegistry definitionRegistry;

    @Inject
    Instance<PluginService> pluginService;

    @Inject
    WorkflowDefinitionCompiler definitionCompiler;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "gamelan.dag.scheduler.enabled", defaultValue = "false")
    boolean dagSchedulerEnabled;

    @ConfigProperty(name = "gamelan.workflow.planning.validate-definition", defaultValue = "true")
    boolean planningValidationEnabled = true;

    Clock clock = Clock.systemUTC();
    private volatile PlanningMetrics planningMetrics;

    /**
     * Evaluate workflow and determine next nodes to execute
     */
    public Uni<ExecutionPlan> planNextExecution(
            WorkflowRun run,
            WorkflowDefinition definition) {

        if (run == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("WorkflowRun cannot be null"));
        }
        if (definition == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("WorkflowDefinition cannot be null"));
        }

        return Uni.createFrom().item(() -> planNextExecutionNow(run, definition));
    }

    private ExecutionPlan planNextExecutionNow(
            WorkflowRun run,
            WorkflowDefinition definition) {

        PlanningMetrics metrics = planningMetrics();
        Timer.Sample sample = metrics.startPlanning();
        try {
            validateDefinitionForPlanning(definition);
            CompiledWorkflowDefinition compiledDefinition = compile(definition);
            ExecutionPlan plan;
            if (run.getStatus() == RunStatus.RUNNING) {
                plan = activePlan(run, definition, compiledDefinition);
            } else {
                LOG.debug("Skipping execution planning for non-running run: {} status={}",
                        safeRunId(run),
                        run.getStatus());
                plan = inactivePlan(run, definition, compiledDefinition);
            }
            metrics.recordPlan(plan, run.getStatus(), sample);
            return plan;
        } catch (RuntimeException e) {
            metrics.recordFailure(sample);
            throw e;
        }
    }

    private void validateDefinitionForPlanning(WorkflowDefinition definition) {
        if (!planningValidationEnabled) {
            return;
        }

        ValidationResult validation;
        try {
            validation = definition.validate();
        } catch (RuntimeException error) {
            throw WorkflowPlanningException.validationFailed(definition, error);
        }

        if (!validation.isValid()) {
            throw WorkflowPlanningException.invalidDefinition(definition, validation);
        }
    }

    private ExecutionPlan activePlan(
            WorkflowRun run,
            WorkflowDefinition definition,
            CompiledWorkflowDefinition compiledDefinition) {

        if (run.getStatus() != RunStatus.RUNNING) {
            return inactivePlan(run, definition, compiledDefinition);
        }

        LOG.debug("Planning next execution for run: {}", safeRunId(run));

        List<NodeId> readyNodes = new ArrayList<>();

        Instant now = Instant.now(clock);

        // Find all nodes that are ready to execute
        for (NodeDefinition node : compiledDefinition.orderedNodes()) {
            if (isNodeReady(run, compiledDefinition, node, now)) {
                readyNodes.add(node.id());
            }
        }
        readyNodes = normalizeReadyNodes(readyNodes, readyNodes);

        if (definition.mode() == WorkflowMode.DAG && dagSchedulerEnabled) {
            readyNodes = normalizeReadyNodes(orderDagReadyNodes(definition, readyNodes), readyNodes);
        }

        // Check for workflow completion
        boolean isComplete = isWorkflowComplete(run, compiledDefinition);

        // Check if workflow is stuck
        boolean isStuck = readyNodes.isEmpty() && !isComplete &&
                run.getStatus() == RunStatus.RUNNING &&
                !hasActiveNodeExecutions(run);

        return new ExecutionPlan(
                readyNodes,
                isComplete,
                isStuck,
                collectWorkflowOutputs(run, definition));
    }

    private ExecutionPlan inactivePlan(
            WorkflowRun run,
            WorkflowDefinition definition,
            CompiledWorkflowDefinition compiledDefinition) {
        boolean isComplete = isWorkflowComplete(run, compiledDefinition);
        return new ExecutionPlan(
                List.of(),
                isComplete,
                false,
                isComplete ? collectWorkflowOutputs(run, definition) : Map.of());
    }

    private String safeRunId(WorkflowRun run) {
        return run != null && run.getId() != null ? run.getId().value() : "<unknown>";
    }

    private List<NodeId> orderDagReadyNodes(WorkflowDefinition definition, List<NodeId> readyNodes) {
        if (readyNodes == null || readyNodes.isEmpty()) {
            return readyNodes;
        }
        if (pluginService == null || !pluginService.isResolvable()) {
            return readyNodes;
        }
        try {
            Class<?> serviceClass = Class.forName("tech.kayys.gamelan.dag.DagSchedulerService");
            PluginService service = pluginService.get();
            var optional = service.getService(serviceClass);
            if (optional.isEmpty()) {
                return readyNodes;
            }
            Object scheduler = optional.get();
            var method = serviceClass.getMethod("orderReadyNodes", WorkflowDefinition.class, List.class);
            Object result = method.invoke(scheduler, definition, readyNodes);
            if (result instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<NodeId> ordered = (List<NodeId>) list;
                return ordered;
            }
        } catch (Exception e) {
            LOG.warn("DAG scheduler not available, using default ordering", e);
        }
        return readyNodes;
    }

    List<NodeId> normalizeReadyNodes(List<NodeId> candidateReadyNodes, List<NodeId> computedReadyNodes) {
        if (computedReadyNodes == null || computedReadyNodes.isEmpty()) {
            return List.of();
        }

        List<NodeId> candidates = candidateReadyNodes != null ? candidateReadyNodes : List.of();
        Set<NodeId> computed = new LinkedHashSet<>();
        for (NodeId nodeId : computedReadyNodes) {
            if (nodeId != null) {
                computed.add(nodeId);
            }
        }
        if (computed.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<NodeId> normalized = new LinkedHashSet<>();
        for (NodeId nodeId : candidates) {
            if (nodeId == null) {
                LOG.warn("Ignoring null ready node from scheduler");
            } else if (!computed.contains(nodeId)) {
                LOG.warn("Ignoring scheduler node that is not ready: {}", nodeId.value());
            } else if (!normalized.add(nodeId)) {
                LOG.debug("Ignoring duplicate scheduler ready node: {}", nodeId.value());
            }
        }

        for (NodeId nodeId : computed) {
            if (normalized.add(nodeId)) {
                LOG.debug("Appending ready node omitted by scheduler: {}", nodeId.value());
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * Check if a node is ready to execute
     */
    private boolean isNodeReady(
            WorkflowRun run,
            CompiledWorkflowDefinition compiledDefinition,
            NodeDefinition node,
            Instant now) {
        // Check if node already executed
        Map<NodeId, NodeExecution> executions = run.getAllNodeExecutions();
        NodeExecution existing = executions.get(node.id());

        if (existing != null) {
            if (existing.getStatus() == NodeExecutionStatus.PENDING) {
                return true;
            }
            return existing.canRetry() && existing.isRetryDue(now);
        }

        // Check if all dependencies are completed
        for (NodeId depId : compiledDefinition.dependencies(node.id())) {
            NodeExecution depExec = executions.get(depId);
            if (depExec == null || depExec.getStatus() != NodeExecutionStatus.COMPLETED) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if workflow is complete
     */
    private boolean isWorkflowComplete(WorkflowRun run, CompiledWorkflowDefinition definition) {
        Map<NodeId, NodeExecution> executions = run.getAllNodeExecutions();

        for (NodeDefinition node : definition.orderedNodes()) {
            NodeExecution exec = executions.get(node.id());
            if (exec == null) {
                return false;
            }
            if (exec.isCompleted()) {
                continue;
            }
            if (exec.isFailed() && !node.isCritical()) {
                continue;
            }
            return false;
        }

        return true;
    }

    private CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
        return definitionCompiler != null
                ? definitionCompiler.compile(definition)
                : CompiledWorkflowDefinition.compile(definition);
    }

    private PlanningMetrics planningMetrics() {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            return PlanningMetrics.NOOP;
        }

        PlanningMetrics current = planningMetrics;
        if (current == null || current.registry != registry) {
            synchronized (this) {
                current = planningMetrics;
                if (current == null || current.registry != registry) {
                    current = new PlanningMetrics(registry);
                    planningMetrics = current;
                }
            }
        }
        return current;
    }

    private boolean hasActiveNodeExecutions(WorkflowRun run) {
        return run.getAllNodeExecutions().values().stream()
                .map(NodeExecution::getStatus)
                .anyMatch(status -> status == NodeExecutionStatus.RUNNING ||
                        status == NodeExecutionStatus.EXECUTING ||
                        status == NodeExecutionStatus.WAITING ||
                        status == NodeExecutionStatus.RETRYING);
    }

    /**
     * Collect workflow outputs from node executions
     */
    private Map<String, Object> collectWorkflowOutputs(
            WorkflowRun run,
            WorkflowDefinition definition) {

        Map<String, Object> outputs = new HashMap<>();

        // Collect outputs defined in workflow definition
        definition.outputs().forEach((outputName, outputDef) -> {
            // Try to find output in context variables
            Object value = run.getContext().getVariable(outputName);
            if (value != null) {
                outputs.put(outputName, value);
            }
        });

        return outputs;
    }

    private static final class PlanningMetrics {
        private static final String OUTCOME_READY = "ready";
        private static final String OUTCOME_WAITING = "waiting";
        private static final String OUTCOME_COMPLETE = "complete";
        private static final String OUTCOME_STUCK = "stuck";
        private static final String OUTCOME_INACTIVE = "inactive";
        private static final String OUTCOME_FAILURE = "failure";

        private static final PlanningMetrics NOOP = new PlanningMetrics();

        private final MeterRegistry registry;
        private final Map<String, Counter> planCounters;
        private final Map<String, Timer> durationTimers;
        private final Map<String, DistributionSummary> readyNodeSummaries;

        private PlanningMetrics() {
            this.registry = null;
            this.planCounters = Map.of();
            this.durationTimers = Map.of();
            this.readyNodeSummaries = Map.of();
        }

        private PlanningMetrics(MeterRegistry registry) {
            this.registry = registry;
            this.planCounters = new ConcurrentHashMap<>();
            this.durationTimers = new ConcurrentHashMap<>();
            this.readyNodeSummaries = new ConcurrentHashMap<>();
        }

        private Timer.Sample startPlanning() {
            return registry != null ? Timer.start(registry) : null;
        }

        private void recordPlan(ExecutionPlan plan, RunStatus runStatus, Timer.Sample sample) {
            if (registry == null) {
                return;
            }
            String outcome = outcome(plan, runStatus);
            counter(outcome).increment();
            stop(sample, timer(outcome));
            readyNodeSummary(outcome).record(plan.readyNodes() != null ? plan.readyNodes().size() : 0);
        }

        private void recordFailure(Timer.Sample sample) {
            if (registry == null) {
                return;
            }
            counter(OUTCOME_FAILURE).increment();
            stop(sample, timer(OUTCOME_FAILURE));
        }

        private Counter counter(String outcome) {
            return planCounters.computeIfAbsent(outcome, key -> Counter.builder("gamelan.workflow.planning.plans")
                    .description("Workflow execution plans produced by bounded outcome")
                    .tag("outcome", key)
                    .register(registry));
        }

        private Timer timer(String outcome) {
            if (registry == null) {
                return null;
            }
            return durationTimers.computeIfAbsent(outcome, key -> Timer.builder("gamelan.workflow.planning.duration")
                    .description("Workflow execution planning duration")
                    .tag("outcome", key)
                    .register(registry));
        }

        private DistributionSummary readyNodeSummary(String outcome) {
            if (registry == null) {
                return null;
            }
            return readyNodeSummaries.computeIfAbsent(outcome, key -> DistributionSummary
                    .builder("gamelan.workflow.planning.ready_nodes")
                    .description("Ready node counts produced by workflow execution planning")
                    .tag("outcome", key)
                    .register(registry));
        }

        private static String outcome(ExecutionPlan plan, RunStatus runStatus) {
            if (runStatus != RunStatus.RUNNING) {
                return OUTCOME_INACTIVE;
            }
            if (plan.isComplete()) {
                return OUTCOME_COMPLETE;
            }
            if (plan.isStuck()) {
                return OUTCOME_STUCK;
            }
            if (plan.readyNodes() != null && !plan.readyNodes().isEmpty()) {
                return OUTCOME_READY;
            }
            return OUTCOME_WAITING;
        }

        private static void stop(Timer.Sample sample, Timer timer) {
            if (sample != null && timer != null) {
                sample.stop(timer);
            }
        }
    }
}
