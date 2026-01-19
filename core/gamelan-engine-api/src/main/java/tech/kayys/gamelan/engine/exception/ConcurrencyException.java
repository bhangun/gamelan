package tech.kayys.gamelan.engine.exception;

public
/**
 * Concurrency Exception
 */
class ConcurrencyException extends RuntimeException {
    public ConcurrencyException(String message) {
        super(message);
    }
}