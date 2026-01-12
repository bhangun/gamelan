package tech.kayys.silat.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tech.kayys.silat.execution.NodeExecutionSnapshot;
import tech.kayys.silat.model.RunStatus;

/**
 * Workflow Run Snapshot Entity
 * Materialized view for fast querying
 */
@Entity
@Table(name = "workflow_runs", indexes = {
        @Index(name = "idx_tenant_status", columnList = "tenant_id, status"),
        @Index(name = "idx_definition_id", columnList = "definition_id"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
public class WorkflowRunEntity {

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "definition_id", nullable = false, length = 128)
    private String definitionId;

    @Column(name = "definition_version", length = 32)
    private String definitionVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RunStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_variables", columnDefinition = "jsonb")
    private Map<String, Object> contextVariables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "node_executions", columnDefinition = "jsonb")
    private Map<String, NodeExecutionSnapshot> nodeExecutions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_path", columnDefinition = "jsonb")
    private List<String> executionPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, String> metadata;

    // Getters and setters
    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public Map<String, Object> getContextVariables() {
        return contextVariables;
    }

    public void setContextVariables(Map<String, Object> contextVariables) {
        this.contextVariables = contextVariables;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public List<String> getExecutionPath() {
        return executionPath;
    }

    public void setExecutionPath(List<String> executionPath) {
        this.executionPath = executionPath;
    }

    public Map<String, NodeExecutionSnapshot> getNodeExecutions() {
        return nodeExecutions;
    }

    public void setNodeExecutions(Map<String, NodeExecutionSnapshot> nodeExecutions) {
        this.nodeExecutions = nodeExecutions;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getDefinitionVersion() {
        return definitionVersion;
    }

    public void setDefinitionVersion(String definitionVersion) {
        this.definitionVersion = definitionVersion;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
