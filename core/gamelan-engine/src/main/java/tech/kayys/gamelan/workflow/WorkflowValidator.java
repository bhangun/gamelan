package tech.kayys.gamelan.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowValidator.class);

    @Inject
    Instance<PluginService> pluginService;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "gamelan.dag.plugin.enabled", defaultValue = "true")
    boolean dagPluginEnabled;

    private volatile ValidationMetrics validationMetrics;

    public Uni<ValidationResult> validate(WorkflowDefinition workflow) {
        ValidationMetrics metrics = validationMetrics();
        Timer.Sample sample = metrics.startValidation();
        if (workflow == null) {
            String error = "Workflow definition cannot be null";
            metrics.recordErrors(ValidationMetrics.SOURCE_NULL_DEFINITION, 1);
            metrics.recordValidation(ValidationMetrics.OUTCOME_NULL_DEFINITION, sample);
            return Uni.createFrom().item(
                    ValidationResult.failure(
                            error,
                            List.of(error)));
        }

        List<String> errors = new ArrayList<>();

        int structuralErrors = 0;
        try {
            ValidationResult structural = workflow.validate();
            if (!structural.isValid()) {
                if (structural.errors().isEmpty()) {
                    errors.add(defaultedMessage(
                            structural.message(),
                            "Workflow definition failed structural validation"));
                    structuralErrors++;
                } else {
                    errors.addAll(structural.errors());
                    structuralErrors += structural.errors().size();
                }
            }
        } catch (RuntimeException error) {
            LOG.warn("Workflow structural validation failed: {}", error.getMessage());
            LOG.debug("Workflow structural validation failure", error);
            errors.add("Workflow structural validation failed: " + safeMessage(error));
            structuralErrors++;
        }
        metrics.recordErrors(ValidationMetrics.SOURCE_STRUCTURAL, structuralErrors);

        PluginValidationResult pluginResult = PluginValidationResult.empty();
        // Run DAG validator plugins only when in DAG mode
        if (workflow.mode() == WorkflowMode.DAG && dagPluginEnabled) {
            pluginResult = runDagPlugins(workflow);
            errors.addAll(pluginResult.errors());
        }
        pluginResult.record(metrics);

        if (!errors.isEmpty()) {
            metrics.recordValidation(ValidationMetrics.OUTCOME_INVALID, sample);
            return Uni.createFrom().item(
                    ValidationResult.failure(String.join("; ", errors), errors));
        }

        metrics.recordValidation(ValidationMetrics.OUTCOME_VALID, sample);
        return Uni.createFrom().item(ValidationResult.success());
    }

    private PluginValidationResult runDagPlugins(WorkflowDefinition workflow) {
        if (pluginService == null || !pluginService.isResolvable()) {
            return PluginValidationResult.empty();
        }

        PluginService service;
        try {
            service = pluginService.get();
        } catch (RuntimeException error) {
            LOG.warn("Workflow validator plugin service could not be resolved: {}", error.getMessage());
            LOG.debug("Workflow validator plugin service resolution failure", error);
            return PluginValidationResult.serviceFailure(
                    "Workflow validator plugin service failed: " + safeMessage(error));
        }

        List<WorkflowValidatorPlugin> plugins;
        try {
            plugins = service != null ? service.getPluginsByType(WorkflowValidatorPlugin.class) : List.of();
        } catch (RuntimeException error) {
            LOG.warn("Workflow validator plugins could not be listed: {}", error.getMessage());
            LOG.debug("Workflow validator plugin listing failure", error);
            return PluginValidationResult.lookupFailure(
                    "Workflow validator plugin lookup failed: " + safeMessage(error));
        }

        if (plugins == null || plugins.isEmpty()) {
            return PluginValidationResult.empty();
        }

        WorkflowValidatorPlugin.WorkflowDefinitionInfo info = toDefinitionInfo(workflow);
        List<String> errors = new ArrayList<>();
        int ruleErrors = 0;
        int runtimeFailures = 0;
        for (WorkflowValidatorPlugin plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            String pluginName = pluginName(plugin);
            List<WorkflowValidatorPlugin.ValidationError> pluginErrors;
            try {
                pluginErrors = plugin.validate(info);
            } catch (RuntimeException error) {
                LOG.warn("Workflow validator plugin {} failed: {}", pluginName, error.getMessage());
                LOG.debug("Workflow validator plugin failure", error);
                errors.add("Workflow validator plugin " + pluginName + " failed: " + safeMessage(error));
                runtimeFailures++;
                continue;
            }
            if (pluginErrors == null || pluginErrors.isEmpty()) {
                continue;
            }
            for (WorkflowValidatorPlugin.ValidationError err : pluginErrors) {
                if (err == null) {
                    continue;
                }
                if (err.severity() == WorkflowValidatorPlugin.ValidationError.Severity.ERROR) {
                    errors.add(pluginErrorMessage(pluginName, err));
                    ruleErrors++;
                }
            }
        }
        return new PluginValidationResult(errors, ruleErrors, runtimeFailures, 0, 0);
    }

    private static String pluginName(WorkflowValidatorPlugin plugin) {
        try {
            var metadata = plugin.getMetadata();
            if (metadata != null && metadata.id() != null && !metadata.id().isBlank()) {
                return metadata.id();
            }
        } catch (RuntimeException ignored) {
            // Fall back to class name when metadata is unavailable.
        }
        return plugin.getClass().getSimpleName();
    }

    private static String pluginErrorMessage(
            String pluginName,
            WorkflowValidatorPlugin.ValidationError error) {
        String message = error.message();
        if (message == null || message.isBlank()) {
            message = "validation failed";
        }
        String location = error.location();
        if (location == null || location.isBlank()) {
            return pluginName + ": " + message;
        }
        return pluginName + " [" + location + "]: " + message;
    }

    private static String safeMessage(RuntimeException error) {
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }

    private static String defaultedMessage(String message, String fallback) {
        return message != null && !message.isBlank() ? message : fallback;
    }

    private ValidationMetrics validationMetrics() {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            return ValidationMetrics.NOOP;
        }

        ValidationMetrics current = validationMetrics;
        if (current == null || current.registry != registry) {
            synchronized (this) {
                current = validationMetrics;
                if (current == null || current.registry != registry) {
                    current = new ValidationMetrics(registry);
                    validationMetrics = current;
                }
            }
        }
        return current;
    }

    private record PluginValidationResult(
            List<String> errors,
            int ruleErrors,
            int runtimeFailures,
            int lookupFailures,
            int serviceFailures) {

        private PluginValidationResult {
            errors = errors != null ? List.copyOf(errors) : List.of();
        }

        private static PluginValidationResult empty() {
            return new PluginValidationResult(List.of(), 0, 0, 0, 0);
        }

        private static PluginValidationResult lookupFailure(String error) {
            return new PluginValidationResult(List.of(error), 0, 0, 1, 0);
        }

        private static PluginValidationResult serviceFailure(String error) {
            return new PluginValidationResult(List.of(error), 0, 0, 0, 1);
        }

        private void record(ValidationMetrics metrics) {
            metrics.recordErrors(ValidationMetrics.SOURCE_PLUGIN_RULE, ruleErrors);
            metrics.recordErrors(ValidationMetrics.SOURCE_PLUGIN_RUNTIME, runtimeFailures);
            metrics.recordErrors(ValidationMetrics.SOURCE_PLUGIN_LOOKUP, lookupFailures);
            metrics.recordErrors(ValidationMetrics.SOURCE_PLUGIN_SERVICE, serviceFailures);
        }
    }

    private static final class ValidationMetrics {
        private static final String OUTCOME_VALID = "valid";
        private static final String OUTCOME_INVALID = "invalid";
        private static final String OUTCOME_NULL_DEFINITION = "null_definition";

        private static final String SOURCE_NULL_DEFINITION = "null_definition";
        private static final String SOURCE_STRUCTURAL = "structural";
        private static final String SOURCE_PLUGIN_RULE = "plugin_rule";
        private static final String SOURCE_PLUGIN_RUNTIME = "plugin_runtime";
        private static final String SOURCE_PLUGIN_LOOKUP = "plugin_lookup";
        private static final String SOURCE_PLUGIN_SERVICE = "plugin_service";

        private static final ValidationMetrics NOOP = new ValidationMetrics();

        private final MeterRegistry registry;
        private final Map<String, Counter> requestCounters;
        private final Map<String, Timer> durationTimers;
        private final Map<String, Counter> errorCounters;

        private ValidationMetrics() {
            this.registry = null;
            this.requestCounters = Map.of();
            this.durationTimers = Map.of();
            this.errorCounters = Map.of();
        }

        private ValidationMetrics(MeterRegistry registry) {
            this.registry = registry;
            this.requestCounters = new ConcurrentHashMap<>();
            this.durationTimers = new ConcurrentHashMap<>();
            this.errorCounters = new ConcurrentHashMap<>();
        }

        private Timer.Sample startValidation() {
            return registry != null ? Timer.start(registry) : null;
        }

        private void recordValidation(String outcome, Timer.Sample sample) {
            if (registry == null) {
                return;
            }
            requestCounter(outcome).increment();
            stop(sample, durationTimer(outcome));
        }

        private void recordErrors(String source, int count) {
            if (registry == null || count <= 0) {
                return;
            }
            errorCounter(source).increment(count);
        }

        private Counter requestCounter(String outcome) {
            return requestCounters.computeIfAbsent(outcome, key -> Counter.builder("gamelan.workflow.validation.requests")
                    .description("Workflow definition validations by bounded outcome")
                    .tag("outcome", key)
                    .register(registry));
        }

        private Timer durationTimer(String outcome) {
            return durationTimers.computeIfAbsent(outcome, key -> Timer.builder("gamelan.workflow.validation.duration")
                    .description("Workflow definition validation duration by bounded outcome")
                    .tag("outcome", key)
                    .register(registry));
        }

        private Counter errorCounter(String source) {
            return errorCounters.computeIfAbsent(source, key -> Counter.builder("gamelan.workflow.validation.errors")
                    .description("Workflow definition validation errors by bounded source")
                    .tag("source", key)
                    .register(registry));
        }

        private static void stop(Timer.Sample sample, Timer timer) {
            if (sample != null && timer != null) {
                sample.stop(timer);
            }
        }
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
}
