package tech.kayys.gamelan.workflow.domain;

import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;

/**
 * Domain model for creating a workflow definition
 */
public record CreateWorkflowDefinitionRequest(
                String name,
                String version,
                String description,
                List<NodeDefinition> nodes,
                Map<String, InputDefinition> inputs,
                Map<String, OutputDefinition> outputs,
                RetryPolicy retryPolicy,
                CompensationPolicy compensationPolicy,
                Map<String, String> metadata) {
}