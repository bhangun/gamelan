package tech.kayys.gamelan.engine.saga;

/**
 * Result of compensation execution
 */
public record CompensationResult(
        boolean success,
        String message) {

    public static CompensationResult success(String message) {
        return new CompensationResult(true, message);
    }

    public static CompensationResult failure(String message) {
        return new CompensationResult(false, message);
    }
}
