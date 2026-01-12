package tech.kayys.silat.model;

/**
 * Node Type - Defines node behavior
 */
public enum NodeType {
    TASK, // Standard task execution
    DECISION, // Conditional branching
    PARALLEL, // Parallel execution
    AGGREGATE, // Wait for multiple branches
    HUMAN_TASK, // Human approval/input
    SUB_WORKFLOW, // Nested workflow
    EVENT_WAIT, // Wait for external event
    TIMER, // Time-based wait
    COMPENSATION, EXECUTOR; // Compensation logic
}
