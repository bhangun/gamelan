package tech.kayys.silat.sdk.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowMetadata;
import tech.kayys.silat.model.NodeDefinition;
import tech.kayys.silat.model.InputDefinition;
import tech.kayys.silat.model.OutputDefinition;
import tech.kayys.silat.model.RetryPolicy;
import tech.kayys.silat.saga.CompensationPolicy;

/**
 * Builder for workflow definitions
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

    WorkflowDefinitionBuilder(WorkflowDefinitionClient client, String name) {
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

        WorkflowDefinition request = new WorkflowDefinition(
                null, // id
                TenantId.of(tenantId),
                name,
                version,
                description,
                nodes,
                inputs,
                outputs,
                metadata,
                retryPolicy,
                compensationPolicy);
        return client.createDefinition(request);
    }
}
