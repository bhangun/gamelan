package tech.kayys.gamelan.engine.workflow;

/**
 * Workflow execution mode.
 * DAG: acyclic, topological scheduling.
 * FLOW: cyclic/agentic workflows allowed.
 * STATE: event-driven/stateful workflows.
 */
public enum WorkflowMode {
    DAG,
    FLOW,
    STATE
}
