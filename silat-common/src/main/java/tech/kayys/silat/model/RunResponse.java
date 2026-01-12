package tech.kayys.silat.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class RunResponse {
    private String runId;
    private String workflowId;
    private String workflowVersion;
    private String status;
    private String phase;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private Integer nodesExecuted;
    private Integer nodesTotal;
    private Integer attemptNumber;
    private Integer maxAttempts;
    private String errorMessage;
    private Map<String, Object> outputs;

    public RunResponse() {}

    public RunResponse(String runId, String workflowId, String workflowVersion, String status, String phase, 
                       Instant createdAt, Instant startedAt, Instant completedAt, Long durationMs, 
                       Integer nodesExecuted, Integer nodesTotal, Integer attemptNumber, 
                       Integer maxAttempts, String errorMessage, Map<String, Object> outputs) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.status = status;
        this.phase = phase;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.nodesExecuted = nodesExecuted;
        this.nodesTotal = nodesTotal;
        this.attemptNumber = attemptNumber;
        this.maxAttempts = maxAttempts;
        this.errorMessage = errorMessage;
        this.outputs = outputs;
    }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }
    public String getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getNodesExecuted() { return nodesExecuted; }
    public void setNodesExecuted(Integer nodesExecuted) { this.nodesExecuted = nodesExecuted; }
    public Integer getNodesTotal() { return nodesTotal; }
    public void setNodesTotal(Integer nodesTotal) { this.nodesTotal = nodesTotal; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Map<String, Object> getOutputs() { return outputs; }
    public void setOutputs(Map<String, Object> outputs) { this.outputs = outputs; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String runId;
        private String workflowId;
        private String workflowVersion;
        private String status;
        private String phase;
        private Instant createdAt;
        private Instant startedAt;
        private Instant completedAt;
        private Long durationMs;
        private Integer nodesExecuted;
        private Integer nodesTotal;
        private Integer attemptNumber;
        private Integer maxAttempts;
        private String errorMessage;
        private Map<String, Object> outputs;

        public Builder runId(String runId) { this.runId = runId; return this; }
        public Builder workflowId(String workflowId) { this.workflowId = workflowId; return this; }
        public Builder workflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder phase(String phase) { this.phase = phase; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { this.completedAt = completedAt; return this; }
        public Builder durationMs(Long durationMs) { this.durationMs = durationMs; return this; }
        public Builder nodesExecuted(Integer nodesExecuted) { this.nodesExecuted = nodesExecuted; return this; }
        public Builder nodesTotal(Integer nodesTotal) { this.nodesTotal = nodesTotal; return this; }
        public Builder attemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; return this; }
        public Builder maxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder outputs(Map<String, Object> outputs) { this.outputs = outputs; return this; }

        public RunResponse build() {
            return new RunResponse(runId, workflowId, workflowVersion, status, phase, createdAt, startedAt, 
                                   completedAt, durationMs, nodesExecuted, nodesTotal, attemptNumber, 
                                   maxAttempts, errorMessage, outputs);
        }
    }
}
