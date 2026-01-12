package tech.kayys.silat.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tech.kayys.silat.model.Transition.TransitionType;

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

        validateTransitions();
        validateConfiguration();
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
            throw new IllegalStateException(
                    "Config key '" + key + "' expected " + type.getSimpleName());
        }
        return Optional.of(type.cast(value));
    }

    public boolean hasConfig(String key) {
        return configuration.containsKey(key);
    }

    // ==================== VALIDATION ====================

    private void validateTransitions() {
        long defaultCount = transitions.stream()
                .filter(Transition::isDefault)
                .count();

        if (defaultCount > 1) {
            throw new IllegalArgumentException(
                    "Node " + id.value() + " has multiple default transitions");
        }
    }

    private void validateConfiguration() {
        if (type == NodeType.EXECUTOR && configuration.isEmpty()) {
            throw new IllegalArgumentException(
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
}
