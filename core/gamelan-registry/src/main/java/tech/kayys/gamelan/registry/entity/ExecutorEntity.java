package tech.kayys.gamelan.registry.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "executors")
public class ExecutorEntity {

    @Id
    @Column(name = "executor_id", nullable = false, length = 255)
    private String executorId;

    @Column(name = "executor_type", nullable = false, length = 255)
    private String executorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "communication_type", nullable = false, length = 50)
    private CommunicationType communicationType;

    @Column(name = "endpoint", nullable = false, length = 500)
    private String endpoint;

    @Column(name = "timeout_seconds")
    private Long timeoutSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "json")
    private Map<String, String> metadata = new HashMap<>();

    // Default constructor for JPA
    public ExecutorEntity() {}

    // Constructor from ExecutorInfo
    public ExecutorEntity(ExecutorInfo executorInfo) {
        this.executorId = executorInfo.executorId();
        this.executorType = executorInfo.executorType();
        this.communicationType = executorInfo.communicationType();
        this.endpoint = executorInfo.endpoint();
        this.timeoutSeconds = executorInfo.timeout() != null ? executorInfo.timeout().getSeconds() : null;
        this.metadata = executorInfo.metadata() != null ? executorInfo.metadata() : new HashMap<>();
    }

    // Convert to ExecutorInfo
    public ExecutorInfo toExecutorInfo() {
        Duration timeout = timeoutSeconds != null ? Duration.ofSeconds(timeoutSeconds) : null;
        return new ExecutorInfo(
            executorId,
            executorType,
            communicationType,
            endpoint,
            timeout,
            metadata
        );
    }

    // Getters and setters
    public String getExecutorId() {
        return executorId;
    }

    public void setExecutorId(String executorId) {
        this.executorId = executorId;
    }

    public String getExecutorType() {
        return executorType;
    }

    public void setExecutorType(String executorType) {
        this.executorType = executorType;
    }

    public CommunicationType getCommunicationType() {
        return communicationType;
    }

    public void setCommunicationType(CommunicationType communicationType) {
        this.communicationType = communicationType;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
}