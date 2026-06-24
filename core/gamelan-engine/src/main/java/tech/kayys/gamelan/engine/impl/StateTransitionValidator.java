package tech.kayys.gamelan.engine.impl;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.ValidationResult;

@ApplicationScoped
public class StateTransitionValidator {

    public ValidationResult validate(RunStatus from, RunStatus to) {
        if (from == null || to == null) {
            return ValidationResult.failure("Transition states cannot be null");
        }

        boolean allowed = from.canTransitionTo(to);
        if (allowed) {
            return ValidationResult.success();
        } else {
            return ValidationResult.failure("Invalid transition " + from + " -> " + to);
        }
    }
}
