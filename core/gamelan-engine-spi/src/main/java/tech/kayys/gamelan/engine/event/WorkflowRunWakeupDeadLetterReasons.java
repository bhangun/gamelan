package tech.kayys.gamelan.engine.event;

/**
 * Stable workflow wake-up dead-letter reasons used by durable outboxes and
 * operator tooling.
 */
public final class WorkflowRunWakeupDeadLetterReasons {

    public static final String MAX_DELIVERY_ATTEMPTS_EXCEEDED = "max-delivery-attempts-exceeded";

    private WorkflowRunWakeupDeadLetterReasons() {
    }
}
