package tech.kayys.gamelan.engine.exception;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Concurrency Exception
 */
public class ConcurrencyException extends GamelanException {
    public ConcurrencyException(String message) {
        super(ErrorCode.CONCURRENCY_CONFLICT, message);
    }
}
