package tech.kayys.gamelan.sdk.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowMetadata;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;

/**
 * Fluent builder for defining a new workflow.
 */
public class WorkflowDefinitionBuilder {

    private final WorkflowDefinitionClient client;
    private final String name;
    private String version = "1.0.0";
    private String tenantId = "default";
    private String description;
    private final List<NodeDefinition> nodes = new ArrayList<>();
    private final Map<String, InputDefinition> inputs = new HashMap<>();
    private final Map<String, OutputDefinition> outputs = new HashMap<>();
    private RetryPolicy retryPolicy;
    private CompensationPolicy compensationPolicy;
    private final Map<String, String> labels = new HashMap<>();

    public WorkflowDefinitionBuilder(WorkflowDefinitionClient client, String name) {
        this.client = client;
        this.name = name;
    }

    public WorkflowDefinitionBuilder version(String version) {
        this.version = version;
        return this;
    }

    public WorkflowDefinitionBuilder tenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    public WorkflowDefinitionBuilder description(String description) {
        this.description = description;
        return this;
    }

    public WorkflowDefinitionBuilder addNode(NodeDefinition node) {
        nodes.add(node);
        return this;
    }

    public WorkflowDefinitionBuilder addInput(String name, InputDefinition input) {
        inputs.put(name, input);
        return this;
    }

    public WorkflowDefinitionBuilder addOutput(String name, OutputDefinition output) {
        outputs.put(name, output);
        return this;
    }

    public WorkflowDefinitionBuilder retryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }

    public WorkflowDefinitionBuilder compensationPolicy(CompensationPolicy policy) {
        this.compensationPolicy = policy;
        return this;
    }

    public WorkflowDefinitionBuilder label(String key, String value) {
        labels.put(key, value);
        return this;
    }

    public Uni<WorkflowDefinition> execute() {
        WorkflowMetadata metadata = new WorkflowMetadata(
                labels,
                new HashMap<>(), // annotations
                Instant.now(),
                "sdk-client");

        WorkflowDefinition definition = new WorkflowDefinition(
                WorkflowDefinitionId.generate(),
                TenantId.of(tenantId),
                name,
                version,
                description,
                tech.kayys.gamelan.engine.workflow.WorkflowMode.FLOW,
                nodes,
                inputs,
                outputs,
                metadata,
                retryPolicy,
                compensationPolicy);

        return client.createWorkflow(definition);
    }
}
