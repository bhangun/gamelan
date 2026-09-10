package tech.kayys.gamelan.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.ValidationResult;

class StateTransitionValidatorTest {

    private final StateTransitionValidator validator = new StateTransitionValidator();

    @Test
    void validate_matchesRunStatusStateMachine() {
        for (RunStatus from : RunStatus.values()) {
            for (RunStatus to : RunStatus.values()) {
                ValidationResult result = validator.validate(from, to);
                assertEquals(from.canTransitionTo(to), result.isValid(), from + " -> " + to);
            }
        }
    }

    @Test
    void validate_rejectsNullStates() {
        assertFalse(validator.validate(null, RunStatus.RUNNING).isValid());
        assertFalse(validator.validate(RunStatus.CREATED, null).isValid());
    }
}
