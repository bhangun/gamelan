package tech.kayys.silat.workflow;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.silat.api.engine.WorkflowRunManager;
import tech.kayys.silat.api.repository.WorkflowRunRepository;
import tech.kayys.silat.dispatcher.TaskDispatcher;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.*;
import tech.kayys.silat.registry.ExecutorRegistry;

import java.util.Optional;

/**
 * Core orchestrator that coordinates planning and dispatching
 */
@Startup
@ApplicationScoped
public class WorkflowOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    @Inject
    EventBus eventBus;

    @Inject
    WorkflowRunManager runManager;

    @Inject
    WorkflowRunRepository runRepository;

    @Inject
    WorkflowDefinitionRegistry definitionRegistry;

    @Inject
    WorkflowExecutionEngine executionEngine;

    @Inject
    ExecutorRegistry executorRegistry;

    @Inject
    tech.kayys.silat.dispatcher.TaskDispatcherAggregator taskDispatcher;

    @jakarta.annotation.PostConstruct
    void init() {
        LOG.info("Initializing WorkflowOrchestrator");

        // 1. Listen for results from executors
        eventBus.<JsonObject>consumer("silat.results")
                .handler(msg -> {
                    NodeExecutionResult result = msg.body().mapTo(NodeExecutionResult.class);
                    LOG.info("Received node result: run={}, node={}, status={}",
                            result.runId().value(), result.nodeId().value(), result.status());

                    runManager.handleNodeResult(result.runId(), result)
                            .subscribe().with(
                                    v -> LOG.debug("Result handled for run: {}", result.runId().value()),
                                    error -> LOG.error("Failed to handle result for run: {}", result.runId().value(),
                                            error));
                });

        // 2. Listen for run updates to drive the workflow
        eventBus.<String>consumer("silat.runs.v1.updated")
                .handler(msg -> {
                    String runId = msg.body();
                    System.out.println("WorkflowOrchestrator SEVERE LOG: Received run update for " + runId);
                    LOG.info("Driving workflow run: {}", runId);
                    drive(WorkflowRunId.of(runId))
                            .subscribe().with(
                                    v -> {
                                        System.out.println(
                                                "WorkflowOrchestrator SEVERE LOG: Drive cycle completed for " + runId);
                                        LOG.info("Drive cycle completed for run: {}", runId);
                                    },
                                    error -> {
                                        System.out.println(
                                                "WorkflowOrchestrator SEVERE LOG: Drive cycle failed for " + runId);
                                        LOG.error("Drive cycle failed for run: {}", runId, error);
                                    });
                });
    }

    /**
     * Drive the workflow cycle: Plan -> Select Executor -> Dispatch
     */
    public Uni<Void> drive(WorkflowRunId runId) {
        return runRepository.findById(runId)
                .flatMap(run -> {
                    if (run == null || run.getStatus().isTerminal()) {
                        return Uni.createFrom().voidItem();
                    }

                    if (run.getStatus() != RunStatus.RUNNING) {
                        return Uni.createFrom().voidItem();
                    }

                    return definitionRegistry.getDefinition(run.getDefinitionId(), run.getTenantId())
                            .flatMap(definition -> {
                                return executionEngine.planNextExecution(run, definition)
                                        .flatMap(plan -> {
                                            if (plan.isComplete()) {
                                                LOG.info("Workflow complete: {}", run.getId().value());
                                                return runManager.completeRun(runId, run.getTenantId(), plan.outputs())
                                                        .replaceWithVoid();
                                            }

                                            if (plan.readyNodes().isEmpty()) {
                                                if (plan.isStuck()) {
                                                    LOG.warn("Workflow stuck: {}", run.getId().value());
                                                }
                                                return Uni.createFrom().voidItem();
                                            }

                                            // Dispatch ready nodes
                                            return Uni.combine().all().unis(
                                                    plan.readyNodes().stream()
                                                            .map(nodeId -> dispatchNode(run, definition, nodeId))
                                                            .toList())
                                                    .discardItems();
                                        });
                            });
                });
    }

    private Uni<Void> dispatchNode(WorkflowRun run, WorkflowDefinition definition, NodeId nodeId) {
        Optional<NodeDefinition> nodeOpt = definition.nodes().stream()
                .filter(n -> n.id().equals(nodeId))
                .findFirst();

        if (nodeOpt.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        NodeDefinition node = nodeOpt.get();

        return executorRegistry.getExecutorForNode(nodeId)
                .flatMap(execOpt -> {
                    if (execOpt.isEmpty()) {
                        LOG.warn("No executor available for node: {}", nodeId.value());
                        // TODO: Handle no executor (retry or fail)
                        return Uni.createFrom().voidItem();
                    }

                    ExecutorInfo executor = execOpt.get();

                    return runManager.createExecutionToken(run.getId(), nodeId, 1)
                            .flatMap(token -> {
                                // NodeExecutionTask(runId, nodeId, attempt, token, context, retryPolicy)
                                NodeExecutionTask task = new NodeExecutionTask(
                                        run.getId(),
                                        nodeId,
                                        1, // attempt
                                        token,
                                        node.configuration(),
                                        node.retryPolicy());

                                return taskDispatcher.dispatch(task, executor);
                            });
                });
    }
}
