package tech.kayys.silat.dto;

import java.util.Map;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * Request DTO for updating a workflow definition
 */
@JsonDeserialize(builder = UpdateWorkflowDefinitionRequest.Builder.class)
public class UpdateWorkflowDefinitionRequest {
    private final String name;
    private final String description;
    private final Map<String, Object> metadata;

    private UpdateWorkflowDefinitionRequest(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.metadata = builder.metadata;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "UpdateWorkflowDefinitionRequest{" +
                "name=\'" + name + "\'" +
                ", description=\'" + description + "\'" +
                ", metadata=" + metadata +
                "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> metadata;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public UpdateWorkflowDefinitionRequest build() {
            return new UpdateWorkflowDefinitionRequest(this);
        }
    }
}
