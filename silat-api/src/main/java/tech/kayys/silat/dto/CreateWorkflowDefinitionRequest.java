package tech.kayys.silat.dto;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * Request to create a workflow definition
 */
@Schema(description = "Request to create a workflow definition")
public record CreateWorkflowDefinitionRequest(
                @NotBlank @Schema(description = "Workflow name", required = true) String name,

                @NotBlank @Schema(description = "Version", required = true) String version,

                @Schema(description = "Description") String description,

                @NotEmpty @Schema(description = "Node definitions", required = true) List<NodeDefinitionDto> nodes,

                @Schema(description = "Input definitions") Map<String, InputDefinitionDto> inputs,

                @Schema(description = "Output definitions") Map<String, OutputDefinitionDto> outputs,

                @Schema(description = "Default retry policy") RetryPolicyDto retryPolicy,

                @Schema(description = "Compensation policy") CompensationPolicyDto compensationPolicy,

                @Schema(description = "Metadata") Map<String, String> metadata) {

        public static Builder builder() {
                return new Builder();
        }

        public static final class Builder {
                private String name;
                private String version;
                private String description;
                private List<NodeDefinitionDto> nodes;
                private Map<String, InputDefinitionDto> inputs;
                private Map<String, OutputDefinitionDto> outputs;
                private RetryPolicyDto retryPolicy;
                private CompensationPolicyDto compensationPolicy;
                private Map<String, String> metadata;

                private Builder() {
                }

                public Builder name(String name) {
                        this.name = name;
                        return this;
                }

                public Builder version(String version) {
                        this.version = version;
                        return this;
                }

                public Builder description(String description) {
                        this.description = description;
                        return this;
                }

                public Builder nodes(List<NodeDefinitionDto> nodes) {
                        this.nodes = nodes;
                        return this;
                }

                public Builder inputs(Map<String, InputDefinitionDto> inputs) {
                        this.inputs = inputs;
                        return this;
                }

                public Builder outputs(Map<String, OutputDefinitionDto> outputs) {
                        this.outputs = outputs;
                        return this;
                }

                public Builder retryPolicy(RetryPolicyDto retryPolicy) {
                        this.retryPolicy = retryPolicy;
                        return this;
                }

                public Builder compensationPolicy(CompensationPolicyDto compensationPolicy) {
                        this.compensationPolicy = compensationPolicy;
                        return this;
                }

                public Builder metadata(Map<String, String> metadata) {
                        this.metadata = metadata;
                        return this;
                }

                public CreateWorkflowDefinitionRequest build() {
                        return new CreateWorkflowDefinitionRequest(name, version, description, nodes, inputs, outputs,
                                        retryPolicy, compensationPolicy, metadata);
                }
        }

}
