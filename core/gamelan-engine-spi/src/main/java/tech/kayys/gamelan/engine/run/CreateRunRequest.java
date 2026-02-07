package tech.kayys.gamelan.engine.run;

import java.util.Map;
import jakarta.validation.constraints.NotNull;

public class CreateRunRequest {
    @NotNull
    private String workflowId;

    @NotNull
    private String workflowVersion;

    private Map<String, Object> inputs;
    private String correlationId;
    private boolean autoStart = true;

    public CreateRunRequest() {
    }

    public CreateRunRequest(String workflowId, String workflowVersion, Map<String, Object> inputs,
            String correlationId, boolean autoStart) {
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.inputs = inputs;
        this.correlationId = correlationId;
        this.autoStart = autoStart;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(String workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String workflowId;
        private String workflowVersion;
        private Map<String, Object> inputs;
        private String correlationId;
        private boolean autoStart = true;

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder workflowVersion(String workflowVersion) {
            this.workflowVersion = workflowVersion;
            return this;
        }

        public Builder inputs(Map<String, Object> inputs) {
            this.inputs = inputs;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder autoStart(boolean autoStart) {
            this.autoStart = autoStart;
            return this;
        }

        public CreateRunRequest build() {
            return new CreateRunRequest(workflowId, workflowVersion, inputs, correlationId, autoStart);
        }
    }

    @Override
    public String toString() {
        return "CreateRunRequest{" +
                "workflowId='" + workflowId + '\'' +
                ", workflowVersion='" + workflowVersion + '\'' +
                ", inputs=" + inputs +
                ", correlationId='" + correlationId + '\'' +
                ", autoStart=" + autoStart +
                '}';
    }
}
