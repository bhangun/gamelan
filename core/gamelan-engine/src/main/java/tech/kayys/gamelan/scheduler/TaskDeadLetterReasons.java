package tech.kayys.gamelan.scheduler;

/**
 * Stable task dead-letter reasons used by workers and operator tooling.
 */
public final class TaskDeadLetterReasons {

    public static final String MAX_DELIVERY_ATTEMPTS_EXCEEDED = "max-delivery-attempts-exceeded";

    private TaskDeadLetterReasons() {
    }
}
