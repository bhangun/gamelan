package tech.kayys.gamelan.engine.impl;

import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.ValidationResult;

@ApplicationScoped
public class StateTransitionValidator {

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
            RunStatus.CREATED, Set.of(RunStatus.RUNNING, RunStatus.CANCELLED),
            RunStatus.RUNNING, Set.of(RunStatus.SUSPENDED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED),
            RunStatus.SUSPENDED, Set.of(RunStatus.RUNNING, RunStatus.CANCELLED),
            RunStatus.COMPLETED, Set.of(),
            RunStatus.FAILED, Set.of(),
            RunStatus.CANCELLED, Set.of());

    public ValidationResult validate(RunStatus from, RunStatus to) {
        boolean allowed = ALLOWED.getOrDefault(from, Set.of()).contains(to);
        if (allowed) {
            return ValidationResult.success();
        } else {
            return ValidationResult.failure("Invalid transition " + from + " -> " + to);
        }
    }
}
