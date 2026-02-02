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
 * Fluent builder for creating workflow definitions.
 * Instances of this builder are obtained via
 * {@link WorkflowDefinitionOperations#create(String)}.
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

    /**
     * Sets the version of the workflow definition.
     * 
     * @param version the version string (default: "1.0.0")
     * @return this builder
     */
    public WorkflowDefinitionBuilder version(String version) {
        this.version = version;
        return this;
    }

    /**
     * Sets the tenant ID for this workflow definition.
     * 
     * @param tenantId the tenant ID (default: "default")
     * @return this builder
     */
    public WorkflowDefinitionBuilder tenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    /**
     * Sets the description of the workflow definition.
     * 
     * @param description a brief description
     * @return this builder
     */
    public WorkflowDefinitionBuilder description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Adds a node definition to the workflow.
     * 
     * @param node the node to add
     * @return this builder
     */
    public WorkflowDefinitionBuilder addNode(NodeDefinition node) {
        nodes.add(node);
        return this;
    }

    /**
     * Adds an input definition to the workflow.
     * 
     * @param name  the input name
     * @param input the input definition
     * @return this builder
     */
    public WorkflowDefinitionBuilder addInput(String name, InputDefinition input) {
        inputs.put(name, input);
        return this;
    }

    /**
     * Adds an output definition to the workflow.
     * 
     * @param name   the output name
     * @param output the output definition
     * @return this builder
     */
    public WorkflowDefinitionBuilder addOutput(String name, OutputDefinition output) {
        outputs.put(name, output);
        return this;
    }

    /**
     * Sets the default retry policy for the workflow.
     * 
     * @param policy the retry policy
     * @return this builder
     */
    public WorkflowDefinitionBuilder retryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }

    /**
     * Sets the default compensation policy for the workflow (Saga pattern).
     * 
     * @param policy the compensation policy
     * @return this builder
     */
    public WorkflowDefinitionBuilder compensationPolicy(CompensationPolicy policy) {
        this.compensationPolicy = policy;
        return this;
    }

    /**
     * Adds a metadata label to the workflow definition.
     * 
     * @param key   the label key
     * @param value the label value
     * @return this builder
     */
    public WorkflowDefinitionBuilder label(String key, String value) {
        labels.put(key, value);
        return this;
    }

    /**
     * Executes the definition creation request.
     * 
     * @return a Uni containing the created workflow definition
     */
    public Uni<WorkflowDefinition> execute() {
        WorkflowMetadata metadata = new WorkflowMetadata(
                labels,
                new HashMap<>(), // annotations
                Instant.now(),
                "sdk-client");

        WorkflowDefinition request = new WorkflowDefinition(
                WorkflowDefinitionId.of(name),
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
