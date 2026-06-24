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
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Admission gate for workflow definitions before they enter persistence.
 */
@ApplicationScoped
public class WorkflowDefinitionAdmissionService {

    @Inject
    WorkflowValidator validator;

    @Inject
    WorkflowDefinitionCompiler definitionCompiler;

    @Inject
    MeterRegistry meterRegistry;

    private volatile AdmissionMetrics admissionMetrics;

    public Uni<WorkflowDefinition> admit(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "WorkflowDefinition cannot be null");
        AdmissionMetrics metrics = admissionMetrics();
        Timer.Sample sample = metrics.startAdmission();
        return validate(definition)
                .onFailure().invoke(error -> metrics.record(AdmissionMetrics.OUTCOME_VALIDATION_FAILURE, sample))
                .flatMap(validation -> {
                    if (!validation.isValid()) {
                        metrics.record(AdmissionMetrics.OUTCOME_REJECTED, sample);
                        return Uni.createFrom().failure(rejected(validation));
                    }
                    return compile(definition)
                            .invoke(admitted -> metrics.record(AdmissionMetrics.OUTCOME_ACCEPTED, sample))
                            .onFailure().invoke(error -> metrics.record(
                                    AdmissionMetrics.OUTCOME_COMPILATION_FAILURE,
                                    sample));
                });
    }

    private Uni<ValidationResult> validate(WorkflowDefinition definition) {
        try {
            Uni<ValidationResult> validation = validator != null
                    ? validator.validate(definition)
                    : Uni.createFrom().item(definition.validate());
            return validation.onFailure().transform(error -> invalidDefinition(
                    "Workflow definition validation failed: " + safeMessage(error),
                    error));
        } catch (RuntimeException error) {
            return Uni.createFrom().failure(invalidDefinition(
                    "Workflow definition validation failed: " + safeMessage(error),
                    error));
        }
    }

    private Uni<WorkflowDefinition> compile(WorkflowDefinition definition) {
        try {
            if (definitionCompiler != null) {
                definitionCompiler.compile(definition);
            }
            return Uni.createFrom().item(definition);
        } catch (RuntimeException error) {
            return Uni.createFrom().failure(invalidDefinition(
                    "Workflow definition compilation failed: " + safeMessage(error),
                    error));
        }
    }

    private static GamelanException rejected(ValidationResult validation) {
        return invalidDefinition("Workflow definition admission rejected: "
                + String.join("; ", validationErrors(validation)));
    }

    private static List<String> validationErrors(ValidationResult validation) {
        if (validation == null) {
            return List.of("validation failed");
        }
        List<String> errors = new ArrayList<>(validation.errors());
        if (!errors.isEmpty()) {
            return errors;
        }
        String message = validation.message();
        if (message != null && !message.isBlank()) {
            return List.of(message);
        }
        return List.of("validation failed");
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }
        String message = error.getMessage();
        return message != null && !message.isBlank()
                ? message
                : error.getClass().getSimpleName();
    }

    private static GamelanException invalidDefinition(String message) {
        return new GamelanException(ErrorCode.WORKFLOW_INVALID_DEFINITION, message);
    }

    private static GamelanException invalidDefinition(String message, Throwable cause) {
        return new GamelanException(ErrorCode.WORKFLOW_INVALID_DEFINITION, message, cause);
    }

    private AdmissionMetrics admissionMetrics() {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            return AdmissionMetrics.NOOP;
        }

        AdmissionMetrics current = admissionMetrics;
        if (current == null || current.registry != registry) {
            synchronized (this) {
                current = admissionMetrics;
                if (current == null || current.registry != registry) {
                    current = new AdmissionMetrics(registry);
                    admissionMetrics = current;
                }
            }
        }
        return current;
    }

    private static final class AdmissionMetrics {
        private static final String OUTCOME_ACCEPTED = "accepted";
        private static final String OUTCOME_REJECTED = "rejected";
        private static final String OUTCOME_VALIDATION_FAILURE = "validation_failure";
        private static final String OUTCOME_COMPILATION_FAILURE = "compilation_failure";

        private static final AdmissionMetrics NOOP = new AdmissionMetrics();

        private final MeterRegistry registry;
        private final Map<String, Counter> admissionCounters;
        private final Map<String, Timer> durationTimers;

        private AdmissionMetrics() {
            this.registry = null;
            this.admissionCounters = Map.of();
            this.durationTimers = Map.of();
        }

        private AdmissionMetrics(MeterRegistry registry) {
            this.registry = registry;
            this.admissionCounters = new ConcurrentHashMap<>();
            this.durationTimers = new ConcurrentHashMap<>();
        }

        private Timer.Sample startAdmission() {
            return registry != null ? Timer.start(registry) : null;
        }

        private void record(String outcome, Timer.Sample sample) {
            if (registry == null) {
                return;
            }
            counter(outcome).increment();
            stop(sample, timer(outcome));
        }

        private Counter counter(String outcome) {
            return admissionCounters.computeIfAbsent(outcome, key -> Counter
                    .builder("gamelan.workflow.definition.admissions")
                    .description("Workflow definition admissions by bounded outcome")
                    .tag("outcome", key)
                    .register(registry));
        }

        private Timer timer(String outcome) {
            return durationTimers.computeIfAbsent(outcome, key -> Timer
                    .builder("gamelan.workflow.definition.admission.duration")
                    .description("Workflow definition admission duration by bounded outcome")
                    .tag("outcome", key)
                    .register(registry));
        }

        private static void stop(Timer.Sample sample, Timer timer) {
            if (sample != null && timer != null) {
                sample.stop(timer);
            }
        }
    }
}
