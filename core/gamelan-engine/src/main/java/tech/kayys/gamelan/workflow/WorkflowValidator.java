package tech.kayys.gamelan.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.plugin.PluginService;
import tech.kayys.gamelan.plugin.validator.WorkflowValidatorPlugin;

/**
 * Workflow validator - Uses dependency-based structure (dependsOn)
 */
@ApplicationScoped
public class WorkflowValidator {

    @Inject
    Instance<PluginService> pluginService;

    @ConfigProperty(name = "gamelan.dag.plugin.enabled", defaultValue = "true")
    boolean dagPluginEnabled;

    public Uni<ValidationResult> validate(WorkflowDefinition workflow) {
        List<String> errors = new ArrayList<>();

        // Check basic structure
        if (workflow.nodes() == null || workflow.nodes().isEmpty()) {
            errors.add("Workflow must have at least one node");
        }

        // Validate dependency references
        if (workflow.nodes() != null) {
            Set<NodeId> nodeIds = workflow.nodes().stream()
                    .map(NodeDefinition::id)
                    .collect(Collectors.toSet());

            for (NodeDefinition node : workflow.nodes()) {
                for (NodeId dependency : node.dependsOn()) {
                    if (!nodeIds.contains(dependency)) {
                        errors.add(
                                "Node " + node.id().value() + " references unknown dependency: " + dependency.value());
                    }
                }
            }
        }

        // Check for cycles only in DAG mode
        if (workflow.nodes() != null && workflow.mode() == WorkflowMode.DAG) {
            if (hasCycles(workflow)) {
                errors.add("Workflow contains cycles (DAG mode does not allow cycles)");
            }
        }

        // Run DAG validator plugins only when in DAG mode
        if (workflow.mode() == WorkflowMode.DAG && dagPluginEnabled) {
            errors.addAll(runDagPlugins(workflow));
        }

        if (!errors.isEmpty()) {
            return Uni.createFrom().item(
                    ValidationResult.failure(String.join("; ", errors)));
        }

        return Uni.createFrom().item(ValidationResult.success());
    }

    private List<String> runDagPlugins(WorkflowDefinition workflow) {
        if (pluginService == null || !pluginService.isResolvable()) {
            return List.of();
        }

        PluginService service = pluginService.get();
        List<WorkflowValidatorPlugin> plugins = service.getPluginsByType(WorkflowValidatorPlugin.class);
        if (plugins == null || plugins.isEmpty()) {
            return List.of();
        }

        WorkflowValidatorPlugin.WorkflowDefinitionInfo info = toDefinitionInfo(workflow);
        List<String> errors = new ArrayList<>();
        for (WorkflowValidatorPlugin plugin : plugins) {
            for (WorkflowValidatorPlugin.ValidationError err : plugin.validate(info)) {
                if (err.severity() == WorkflowValidatorPlugin.ValidationError.Severity.ERROR) {
                    errors.add(err.message());
                }
            }
        }
        return errors;
    }

    private WorkflowValidatorPlugin.WorkflowDefinitionInfo toDefinitionInfo(WorkflowDefinition workflow) {
        return new WorkflowValidatorPlugin.WorkflowDefinitionInfo() {
            @Override
            public String definitionId() {
                return workflow.id().value();
            }

            @Override
            public String name() {
                return workflow.name();
            }

            @Override
            public String version() {
                return workflow.version();
            }

            @Override
            public List<WorkflowValidatorPlugin.NodeDefinitionInfo> nodes() {
                return workflow.nodes().stream()
                        .map(n -> (WorkflowValidatorPlugin.NodeDefinitionInfo) new WorkflowValidatorPlugin.NodeDefinitionInfo() {
                            @Override
                            public String nodeId() {
                                return n.id().value();
                            }

                            @Override
                            public String nodeType() {
                                return n.type().name();
                            }

                            @Override
                            public java.util.Map<String, Object> configuration() {
                                return n.configuration();
                            }
                        }).toList();
            }

            @Override
            public List<WorkflowValidatorPlugin.TransitionInfo> transitions() {
                List<WorkflowValidatorPlugin.TransitionInfo> transitions = new ArrayList<>();
                for (NodeDefinition node : workflow.nodes()) {
                    for (NodeId dep : node.dependsOn()) {
                        transitions.add(new WorkflowValidatorPlugin.TransitionInfo() {
                            @Override
                            public String fromNodeId() {
                                return dep.value();
                            }

                            @Override
                            public String toNodeId() {
                                return node.id().value();
                            }

                            @Override
                            public String condition() {
                                return null;
                            }
                        });
                    }
                }
                return transitions;
            }
        };
    }

    private boolean hasCycles(WorkflowDefinition workflow) {
        // Simple cycle detection using DFS
        Map<NodeId, Set<NodeId>> adjacency = new HashMap<>();

        for (NodeDefinition node : workflow.nodes()) {
            for (NodeId dependency : node.dependsOn()) {
                // Dependency model: dependency -> node
                adjacency.computeIfAbsent(dependency, k -> new HashSet<>())
                        .add(node.id());
            }
        }

        Set<NodeId> visited = new HashSet<>();
        Set<NodeId> recursionStack = new HashSet<>();

        for (NodeDefinition node : workflow.nodes()) {
            if (hasCycleDFS(node.id(), adjacency, visited, recursionStack)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasCycleDFS(
            NodeId nodeId,
            Map<NodeId, Set<NodeId>> adjacency,
            Set<NodeId> visited,
            Set<NodeId> recursionStack) {

        if (recursionStack.contains(nodeId)) {
            return true;
        }

        if (visited.contains(nodeId)) {
            return false;
        }

        visited.add(nodeId);
        recursionStack.add(nodeId);

        Set<NodeId> neighbors = adjacency.get(nodeId);
        if (neighbors != null) {
            for (NodeId neighbor : neighbors) {
                if (hasCycleDFS(neighbor, adjacency, visited, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(nodeId);
        return false;
    }
}
