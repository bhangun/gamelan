package tech.kayys.gamelan.plugin.impl;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public class DefaultWorkflowContext implements WorkflowContext {

    private final WorkflowRunId runId;
    private final WorkflowDefinitionId definitionId;
    private final TenantId tenantId;

    private RunStatus status;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final Map<NodeId, NodeResult> completedNodes = new ConcurrentHashMap<>();

    private Instant startedAt;
    private Instant updatedAt;

    public DefaultWorkflowContext(
            WorkflowRunId runId,
            WorkflowDefinitionId definitionId,
            TenantId tenantId) {
        this.runId = runId;
        this.definitionId = definitionId;
        this.tenantId = tenantId;
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @Override
    public Map<NodeId, NodeResult> completedNodes() {
        return Map.copyOf(completedNodes);
    }

    @Override
    public WorkflowDefinitionId definitionId() {
        return definitionId;
    }

    @Override
    public WorkflowRunId runId() {
        return runId;
    }

    @Override
    public Instant startedAt() {
        return startedAt;
    }

    @Override
    public RunStatus status() {
        return status;
    }

    @Override
    public TenantId tenantId() {
        return tenantId;
    }

    @Override
    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public Map<String, Object> variables() {
        return Map.copyOf(variables);
    }
}
