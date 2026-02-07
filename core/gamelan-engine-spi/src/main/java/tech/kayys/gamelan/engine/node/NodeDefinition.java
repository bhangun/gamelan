package tech.kayys.gamelan.engine.node;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.run.Transition.TransitionType;

/**
 * Node Definition - Individual step in workflow
 */
/**
 * Node Definition - Individual step in workflow
 * Immutable, validated, future-proof
 */
public record NodeDefinition(
        NodeId id,
        String name,
        NodeType type,
        String executorType,
        Map<String, Object> configuration,
        List<NodeId> dependsOn,
        List<Transition> transitions,
        RetryPolicy retryPolicy,
        Duration timeout,
        boolean critical) {
    public NodeDefinition {
        Objects.requireNonNull(id, "Node ID cannot be null");
        Objects.requireNonNull(type, "Node type cannot be null");
        Objects.requireNonNull(executorType, "Executor type cannot be null");

        name = (name != null && !name.isBlank()) ? name : id.value();

        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        transitions = transitions != null ? List.copyOf(transitions) : List.of();
        configuration = configuration != null ? Map.copyOf(configuration) : Map.of();

        retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.none();
        timeout = timeout != null ? timeout : Duration.ZERO;

        validateTransitions(transitions, id);
        validateConfiguration(type, configuration, id);
    }

    // ==================== ROLE ====================

    public boolean isStartNode() {
        return dependsOn.isEmpty();
    }

    public boolean isEndNode() {
        return transitions.isEmpty();
    }

    public boolean isCritical() {
        return critical;
    }

    // ==================== TRANSITIONS ====================

    public List<Transition> transitionsFor(TransitionType type) {
        return transitions.stream()
                .filter(t -> t.type() == type)
                .toList();
    }

    public Optional<Transition> defaultTransition() {
        return transitions.stream()
                .filter(Transition::isDefault)
                .findFirst();
    }

    // ==================== CONFIG ====================

    public <T> Optional<T> config(String key, Class<T> type) {
        Object value = configuration.get(key);
        if (value == null)
            return Optional.empty();
        if (!type.isInstance(value)) {
            throw new GamelanException(
                    ErrorCode.CONFIG_INVALID,
                    "Config key '" + key + "' expected " + type.getSimpleName());
        }
        return Optional.of(type.cast(value));
    }

    public boolean hasConfig(String key) {
        return configuration.containsKey(key);
    }

    // ==================== VALIDATION ====================

    private void validateTransitions(List<Transition> transitions, NodeId id) {
        long defaultCount = transitions.stream()
                .filter(Transition::isDefault)
                .count();

        if (defaultCount > 1) {
            throw new GamelanException(
                    ErrorCode.WORKFLOW_INVALID_DEFINITION,
                    "Node " + id.value() + " has multiple default transitions");
        }
    }

    private void validateConfiguration(NodeType type, Map<String, Object> configuration, NodeId id) {
        if (type == NodeType.EXECUTOR && configuration.isEmpty()) {
            throw new GamelanException(
                    ErrorCode.CONFIG_MISSING,
                    "Executor node must have configuration: " + id.value());
        }
    }

    // ==================== INTROSPECTION ====================

    public boolean hasRetry() {
        return retryPolicy.maxAttempts() > 1;
    }

    public boolean hasTimeout() {
        return !timeout.isZero() && !timeout.isNegative();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private NodeId id;
        private String name;
        private NodeType type = NodeType.EXECUTOR;
        private String executorType = "unspecified";
        private Map<String, Object> configuration = new java.util.HashMap<>();
        private List<NodeId> dependsOn = new java.util.ArrayList<>();
        private List<Transition> transitions = new java.util.ArrayList<>();
        private RetryPolicy retryPolicy;
        private Duration timeout;
        private boolean critical;

        public Builder id(NodeId id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(NodeType type) {
            this.type = type;
            return this;
        }

        public Builder type(String type) {
            this.executorType = type;
            return this;
        }

        public Builder executorType(String executorType) {
            this.executorType = executorType;
            return this;
        }

        public Builder configuration(Map<String, Object> configuration) {
            this.configuration = new java.util.HashMap<>(configuration);
            return this;
        }

        public Builder addConfig(String key, Object value) {
            this.configuration.put(key, value);
            return this;
        }

        public Builder dependsOn(List<NodeId> dependsOn) {
            this.dependsOn = new java.util.ArrayList<>(dependsOn);
            return this;
        }

        public Builder addDependency(NodeId nodeId) {
            this.dependsOn.add(nodeId);
            return this;
        }

        public Builder transitions(List<Transition> transitions) {
            this.transitions = new java.util.ArrayList<>(transitions);
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder critical(boolean critical) {
            this.critical = critical;
            return this;
        }

        public Builder isStartNode(boolean start) {
            // No-op for now as it's determined by dependsOn
            return this;
        }

        public Builder isEndNode(boolean end) {
            // No-op for now as it's determined by transitions
            return this;
        }

        public NodeDefinition build() {
            return new NodeDefinition(
                    id,
                    name,
                    type,
                    executorType,
                    configuration,
                    dependsOn,
                    transitions,
                    retryPolicy,
                    timeout,
                    critical);
        }
    }
}
