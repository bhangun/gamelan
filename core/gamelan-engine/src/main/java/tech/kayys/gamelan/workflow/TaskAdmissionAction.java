package tech.kayys.gamelan.workflow;

/**
 * Stable orchestration admission actions for executor assignment.
 */
public enum TaskAdmissionAction {
    DISPATCH("dispatch"),
    WAIT_FOR_EXECUTOR("wait_for_executor"),
    REJECT("reject"),
    DEAD_LETTER("dead_letter"),
    DEFER_CAPACITY("defer_capacity");

    private final String metricName;

    TaskAdmissionAction(String metricName) {
        this.metricName = metricName;
    }

    public String metricName() {
        return metricName;
    }
}
