package tech.kayys.silat.saga;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.model.NodeDefinition;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowRun;
import tech.kayys.silat.workflow.WorkflowDefinitionRegistry;

/**
 * Coordinates compensation (saga pattern) for failed workflows
 */
@ApplicationScoped
public class CompensationCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(CompensationCoordinator.class);

    @Inject
    WorkflowDefinitionRegistry definitionRegistry;

    /**
     * Execute compensation for a failed workflow
     */
    public Uni<CompensationResult> compensate(WorkflowRun run) {
        LOG.info("Starting compensation for run: {}", run.getId().value());

        return definitionRegistry.getDefinition(run.getDefinitionId(), run.getTenantId())
                .flatMap(definition -> {
                    CompensationPolicy policy = definition.compensationPolicy();

                    if (policy == null) {
                        LOG.warn("No compensation policy defined");
                        return Uni.createFrom().item(
                                new CompensationResult(true, "No compensation needed"));
                    }

                    // Get nodes to compensate (completed nodes in reverse order)
                    List<NodeId> completedNodes = getCompletedNodes(run);

                    if (completedNodes.isEmpty()) {
                        return Uni.createFrom().item(
                                new CompensationResult(true, "No nodes to compensate"));
                    }

                    return executeCompensationStrategy(
                            run, definition, completedNodes, policy);
                });
    }

    /**
     * Get list of completed nodes that need compensation
     */
    private List<NodeId> getCompletedNodes(WorkflowRun run) {
        List<NodeId> completed = new ArrayList<>();

        run.getAllNodeExecutions().forEach((nodeId, execution) -> {
            if (execution.isCompleted()) {
                completed.add(nodeId);
            }
        });

        // Reverse order for sequential compensation
        Collections.reverse(completed);

        return completed;
    }

    /**
     * Execute compensation based on strategy
     */
    private Uni<CompensationResult> executeCompensationStrategy(
            WorkflowRun run,
            WorkflowDefinition definition,
            List<NodeId> nodesToCompensate,
            CompensationPolicy policy) {

        return switch (policy.strategy()) {
            case SEQUENTIAL -> executeSequentialCompensation(
                    run, definition, nodesToCompensate, policy);
            case PARALLEL -> executeParallelCompensation(
                    run, definition, nodesToCompensate, policy);
            case CUSTOM -> executeCustomCompensation(
                    run, definition, nodesToCompensate, policy);
        };
    }

    /**
     * Sequential compensation (one by one in reverse order)
     */
    private Uni<CompensationResult> executeSequentialCompensation(
            WorkflowRun run,
            WorkflowDefinition definition,
            List<NodeId> nodesToCompensate,
            CompensationPolicy policy) {

        LOG.info("Executing sequential compensation for {} nodes",
                nodesToCompensate.size());

        Uni<CompensationResult> chain = Uni.createFrom().item(
                new CompensationResult(true, "Starting compensation"));

        for (NodeId nodeId : nodesToCompensate) {
            chain = chain.flatMap(previousResult -> {
                if (!previousResult.success() && policy.failOnCompensationError()) {
                    return Uni.createFrom().item(previousResult);
                }

                return compensateNode(run, definition, nodeId)
                        .onFailure().recoverWithItem(error -> {
                            LOG.error("Compensation failed for node: {}",
                                    nodeId.value(), error);
                            return new CompensationResult(false,
                                    "Compensation failed: " + error.getMessage());
                        });
            });
        }

        return chain.map(result -> new CompensationResult(true,
                "Sequential compensation completed"));
    }

    /**
     * Parallel compensation (all at once)
     */
    private Uni<CompensationResult> executeParallelCompensation(
            WorkflowRun run,
            WorkflowDefinition definition,
            List<NodeId> nodesToCompensate,
            CompensationPolicy policy) {

        LOG.info("Executing parallel compensation for {} nodes",
                nodesToCompensate.size());

        List<Uni<CompensationResult>> compensations = nodesToCompensate.stream()
                .map(nodeId -> compensateNode(run, definition, nodeId)
                        .onFailure().recoverWithItem(error -> {
                            LOG.error("Compensation failed for node: {}",
                                    nodeId.value(), error);
                            return new CompensationResult(false,
                                    "Failed: " + error.getMessage());
                        }))
                .toList();

        return Uni.join().all(compensations).andFailFast()
                .map(results -> {
                    boolean allSuccess = results.stream()
                            .allMatch(CompensationResult::success);

                    return new CompensationResult(allSuccess,
                            "Parallel compensation completed");
                });
    }

    /**
     * Custom compensation (hook for extensions)
     */
    private Uni<CompensationResult> executeCustomCompensation(
            WorkflowRun run,
            WorkflowDefinition definition,
            List<NodeId> nodesToCompensate,
            CompensationPolicy policy) {

        LOG.warn("Custom compensation not implemented, using sequential");
        return executeSequentialCompensation(
                run, definition, nodesToCompensate, policy);
    }

    /**
     * Compensate a single node
     */
    private Uni<CompensationResult> compensateNode(
            WorkflowRun run,
            WorkflowDefinition definition,
            NodeId nodeId) {

        LOG.debug("Compensating node: {}", nodeId.value());

        // Find node definition
        Optional<NodeDefinition> nodeDefOpt = definition.findNode(nodeId);
        if (nodeDefOpt.isEmpty()) {
            return Uni.createFrom().item(
                    new CompensationResult(false, "Node not found"));
        }

        NodeDefinition nodeDef = nodeDefOpt.get();

        // Check if node has compensation handler
        Object compensationHandler = nodeDef.configuration().get("compensationHandler");

        if (compensationHandler == null) {
            LOG.debug("No compensation handler for node: {}", nodeId.value());
            return Uni.createFrom().item(
                    new CompensationResult(true, "No compensation needed"));
        }

        // Execute compensation handler
        // In real implementation, this would invoke the compensation executor
        return Uni.createFrom().item(
                new CompensationResult(true, "Node compensated"))
                .onItem().delayIt().by(Duration.ofMillis(100)); // Simulate work
    }
}
