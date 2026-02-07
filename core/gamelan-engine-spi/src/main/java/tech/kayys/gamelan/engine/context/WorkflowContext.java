package tech.kayys.gamelan.engine.context;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface WorkflowContext {

    WorkflowRunId runId();

    WorkflowDefinitionId definitionId();

    TenantId tenantId();

    RunStatus status();

    Instant startedAt();

    Instant updatedAt();

    Map<String, Object> variables();

    Map<NodeId, NodeResult> completedNodes();
}
