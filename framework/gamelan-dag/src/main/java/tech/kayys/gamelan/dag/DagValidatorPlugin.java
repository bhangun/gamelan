package tech.kayys.gamelan.dag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginException;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginMetadataBuilder;
import tech.kayys.gamelan.engine.plugin.ServiceRegistry;
import tech.kayys.gamelan.plugin.validator.WorkflowValidatorPlugin;

/**
 * Optional DAG validator plugin.
 * Enforces acyclic workflow semantics when DAG mode is enabled.
 */
public class DagValidatorPlugin implements WorkflowValidatorPlugin {

    @Inject
    DagValidatorConfig config;

    private ServiceRegistry serviceRegistry;

    private static final PluginMetadata METADATA = PluginMetadataBuilder.builder()
            .id("gamelan-dag")
            .name("Gamelan DAG Validator")
            .version("1.0.0")
            .author("Wayang")
            .description("DAG validation rules for Gamelan workflows")
            .build();

    @Override
    public void initialize(PluginContext context) throws PluginException {
        if (context == null) {
            return;
        }
        serviceRegistry = context.getServiceRegistry();
        if (serviceRegistry != null && !serviceRegistry.hasService(DagSchedulerService.class)) {
            serviceRegistry.registerService(DagSchedulerService.class, new DefaultDagSchedulerService());
        }
    }

    @Override
    public void start() throws PluginException {
        // No-op for now
    }

    @Override
    public void stop() throws PluginException {
        if (serviceRegistry != null && serviceRegistry.hasService(DagSchedulerService.class)) {
            serviceRegistry.unregisterService(DagSchedulerService.class);
        }
    }

    @Override
    public PluginMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public List<ValidationError> validate(WorkflowDefinitionInfo definition) {
        List<ValidationError> errors = new ArrayList<>();

        // Check if DAG validation is enabled
        if (!isDagValidatorEnabled()) {
            return errors;
        }

        if (definition.nodes() == null || definition.nodes().isEmpty()) {
            errors.add(error("dag.empty", "Workflow must have at least one node", "workflow"));
            return errors;
        }

        // Build adjacency map once for efficiency
        Map<String, Set<String>> adjacency = buildAdjacency(definition);

        // Check for cycles - mandatory for DAG
        if (hasCycle(adjacency)) {
            List<String> cyclePath = findCyclePath(adjacency);
            String cycleDescription = cyclePath != null ?
                "Cycle detected: " + String.join(" -> ", cyclePath) :
                "DAG mode does not allow cycles";
            errors.add(error("dag.cycle", cycleDescription, "workflow"));
        }

        // Check for single root if required
        if (!isAllowMultipleRoots()) {
            long roots = countRootNodes(adjacency, definition.nodes());
            if (roots == 0) {
                errors.add(error("dag.no-root", "DAG must have at least one root node", "workflow"));
            } else if (roots != 1) {
                errors.add(error("dag.multiple-roots", "DAG must have exactly one root node, found: " + roots, "workflow"));
            }
        }

        // Check for orphan nodes if required
        if (!isAllowOrphanNodes()) {
            Set<String> orphanNodes = findOrphanNodes(adjacency, definition.nodes());
            for (String orphanNodeId : orphanNodes) {
                errors.add(error("dag.orphan", "Orphan node detected: " + orphanNodeId, orphanNodeId));
            }
        }

        // Additional validations for DAG completeness
        errors.addAll(validateNodeConnectivity(adjacency, definition.nodes()));

        // Validate depth and width constraints if configured
        errors.addAll(validateSizeConstraints(adjacency, definition.nodes()));

        return errors;
    }

    @Override
    public List<String> getValidationRules() {
        List<String> rules = new ArrayList<>();
        rules.add("No cycles");
        if (!isAllowMultipleRoots()) {
            rules.add("Single root (configurable)");
        } else {
            rules.add("Multiple roots allowed (configurable)");
        }
        if (!isAllowOrphanNodes()) {
            rules.add("No orphan nodes (configurable)");
        } else {
            rules.add("Orphan nodes allowed (configurable)");
        }
        rules.add("All nodes must be reachable from root");
        rules.add("Depth and width constraints (configurable)");
        return rules;
    }

    private ValidationError error(String rule, String message, String location) {
        return new ValidationError(rule, message, location, ValidationError.Severity.ERROR);
    }

    private ValidationError warning(String rule, String message, String location) {
        return new ValidationError(rule, message, location, ValidationError.Severity.WARNING);
    }

    private Map<String, Set<String>> buildAdjacency(WorkflowDefinitionInfo definition) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (TransitionInfo t : definition.transitions()) {
            adjacency.computeIfAbsent(t.fromNodeId(), k -> new HashSet<>()).add(t.toNodeId());
        }
        return adjacency;
    }

    private int incomingCount(Map<String, Set<String>> adjacency, String nodeId) {
        int count = 0;
        for (Set<String> targets : adjacency.values()) {
            if (targets.contains(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private long countRootNodes(Map<String, Set<String>> adjacency, List<NodeDefinitionInfo> nodes) {
        return nodes.stream()
                .filter(n -> incomingCount(adjacency, n.nodeId()) == 0)
                .count();
    }

    private Set<String> findOrphanNodes(Map<String, Set<String>> adjacency, List<NodeDefinitionInfo> nodes) {
        Set<String> orphanNodes = new HashSet<>();
        for (NodeDefinitionInfo node : nodes) {
            // A node is an orphan if it has no incoming edges AND no outgoing edges
            boolean hasIncoming = incomingCount(adjacency, node.nodeId()) > 0;
            boolean hasOutgoing = adjacency.containsKey(node.nodeId()) && !adjacency.get(node.nodeId()).isEmpty();

            if (!hasIncoming && !hasOutgoing) {
                orphanNodes.add(node.nodeId());
            }
        }
        return orphanNodes;
    }

    private List<ValidationError> validateNodeConnectivity(Map<String, Set<String>> adjacency, List<NodeDefinitionInfo> nodes) {
        List<ValidationError> errors = new ArrayList<>();

        // Find all root nodes (nodes with no incoming edges)
        Set<String> rootNodes = new HashSet<>();
        for (NodeDefinitionInfo node : nodes) {
            if (incomingCount(adjacency, node.nodeId()) == 0) {
                rootNodes.add(node.nodeId());
            }
        }

        // Perform reachability analysis from all root nodes
        Set<String> reachableNodes = new HashSet<>();
        for (String rootNode : rootNodes) {
            reachableNodes.addAll(findReachableNodes(rootNode, adjacency));
        }

        // Check if all nodes are reachable
        for (NodeDefinitionInfo node : nodes) {
            if (!reachableNodes.contains(node.nodeId())) {
                errors.add(error("dag.unreachable", "Node is unreachable: " + node.nodeId(), node.nodeId()));
            }
        }

        return errors;
    }

    private List<ValidationError> validateSizeConstraints(Map<String, Set<String>> adjacency, List<NodeDefinitionInfo> nodes) {
        List<ValidationError> errors = new ArrayList<>();

        // Calculate depth of the DAG
        int depth = calculateMaxDepth(adjacency, nodes);
        if (depth > getMaxDepth()) {
            errors.add(error("dag.depth-limit",
                String.format("DAG depth (%d) exceeds maximum allowed depth (%d)", depth, getMaxDepth()),
                "workflow"));
        }

        // Calculate width of the DAG (max number of nodes at any level)
        int width = calculateMaxWidth(adjacency, nodes);
        if (width > getMaxWidth()) {
            errors.add(error("dag.width-limit",
                String.format("DAG width (%d) exceeds maximum allowed width (%d)", width, getMaxWidth()),
                "workflow"));
        }

        return errors;
    }

    private int calculateMaxDepth(Map<String, Set<String>> adjacency, List<NodeDefinitionInfo> nodes) {
        // Find all root nodes
        Set<String> rootNodes = new HashSet<>();
        for (NodeDefinitionInfo node : nodes) {
            if (incomingCount(adjacency, node.nodeId()) == 0) {
                rootNodes.add(node.nodeId());
            }
        }

        int maxDepth = 0;
        for (String rootNode : rootNodes) {
            maxDepth = Math.max(maxDepth, calculateDepthFromNode(rootNode, adjacency));
        }

        return maxDepth;
    }

    private int calculateDepthFromNode(String startNode, Map<String, Set<String>> adjacency) {
        Map<String, Integer> depths = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.offer(startNode);
        depths.put(startNode, 1);

        while (!queue.isEmpty()) {
            String currentNode = queue.poll();
            int currentDepth = depths.get(currentNode);

            Set<String> children = adjacency.get(currentNode);
            if (children != null) {
                for (String child : children) {
                    int childDepth = Math.max(depths.getOrDefault(child, 0), currentDepth + 1);
                    depths.put(child, childDepth);
                    queue.offer(child);
                }
            }
        }

        return depths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private int calculateMaxWidth(Map<String, Set<String>> adjacency, List<NodeDefinitionInfo> nodes) {
        // Find all root nodes
        Set<String> rootNodes = new HashSet<>();
        for (NodeDefinitionInfo node : nodes) {
            if (incomingCount(adjacency, node.nodeId()) == 0) {
                rootNodes.add(node.nodeId());
            }
        }

        // Track nodes at each depth level
        Map<Integer, Set<String>> levels = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> nodeLevels = new HashMap<>();

        // Initialize root nodes at level 0
        for (String rootNode : rootNodes) {
            queue.offer(rootNode);
            nodeLevels.put(rootNode, 0);
            levels.computeIfAbsent(0, k -> new HashSet<>()).add(rootNode);
        }

        // BFS to assign levels to all nodes
        while (!queue.isEmpty()) {
            String currentNode = queue.poll();
            int currentLevel = nodeLevels.get(currentNode);

            Set<String> children = adjacency.get(currentNode);
            if (children != null) {
                for (String child : children) {
                    int childLevel = currentLevel + 1;
                    if (!nodeLevels.containsKey(child) || nodeLevels.get(child) < childLevel) {
                        nodeLevels.put(child, childLevel);
                        levels.computeIfAbsent(childLevel, k -> new HashSet<>()).add(child);
                        queue.offer(child);
                    }
                }
            }
        }

        // Find the maximum number of nodes at any level
        return levels.values().stream()
            .mapToInt(Set::size)
            .max()
            .orElse(0);
    }

    private Set<String> findReachableNodes(String startNode, Map<String, Set<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.offer(startNode);

        while (!queue.isEmpty()) {
            String currentNode = queue.poll();
            if (visited.contains(currentNode)) {
                continue;
            }

            visited.add(currentNode);

            Set<String> children = adjacency.get(currentNode);
            if (children != null) {
                for (String child : children) {
                    if (!visited.contains(child)) {
                        queue.offer(child);
                    }
                }
            }
        }

        return visited;
    }

    private boolean hasCycle(Map<String, Set<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (String nodeId : adjacency.keySet()) {
            if (hasCycleDfs(nodeId, adjacency, visited, recStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleDfs(String nodeId, Map<String, Set<String>> adjacency,
                                Set<String> visited, Set<String> recStack) {
        if (recStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }
        visited.add(nodeId);
        recStack.add(nodeId);
        for (String next : adjacency.getOrDefault(nodeId, Set.of())) {
            if (hasCycleDfs(next, adjacency, visited, recStack)) {
                return true;
            }
        }
        recStack.remove(nodeId);
        return false;
    }

    /**
     * Enhanced cycle detection that returns the actual cycle path for better error reporting
     */
    private List<String> findCyclePath(Map<String, Set<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Map<String, String> parent = new HashMap<>(); // To track parent of each node in DFS
        Map<String, Boolean> recStack = new HashMap<>(); // Recursion stack

        for (String nodeId : adjacency.keySet()) {
            if (!visited.contains(nodeId)) {
                List<String> cycle = findCycleDfs(nodeId, adjacency, visited, parent, recStack, new ArrayList<>());
                if (cycle != null && !cycle.isEmpty()) {
                    return cycle;
                }
            }
        }
        return null;
    }

    private List<String> findCycleDfs(String nodeId, Map<String, Set<String>> adjacency,
                                      Set<String> visited, Map<String, String> parent,
                                      Map<String, Boolean> recStack, List<String> path) {
        visited.add(nodeId);
        recStack.put(nodeId, true);
        path.add(nodeId);

        Set<String> neighbors = adjacency.getOrDefault(nodeId, Set.of());
        for (String neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                parent.put(neighbor, nodeId);
                List<String> cycle = findCycleDfs(neighbor, adjacency, visited, parent, recStack, path);
                if (cycle != null) {
                    return cycle;
                }
            } else if (recStack.getOrDefault(neighbor, false)) {
                // Found a back edge, indicating a cycle
                List<String> cycle = new ArrayList<>();
                int idx = path.indexOf(neighbor);
                if (idx != -1) {
                    cycle.addAll(path.subList(idx, path.size()));
                    cycle.add(neighbor); // Complete the cycle
                }
                return cycle;
            }
        }

        path.remove(path.size() - 1);
        recStack.put(nodeId, false);
        return null;
    }

    private boolean isDagValidatorEnabled() {
        return config == null || config.isDagValidatorEnabled();
    }

    private boolean isAllowMultipleRoots() {
        return config != null && config.isAllowMultipleRoots();
    }

    private boolean isAllowOrphanNodes() {
        return config != null && config.isAllowOrphanNodes();
    }

    private int getMaxDepth() {
        return config == null ? 100 : config.getMaxDepth();
    }

    private int getMaxWidth() {
        return config == null ? 50 : config.getMaxWidth();
    }
}
