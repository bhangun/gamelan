package tech.kayys.gamelan.engine.executor;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.DurationSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.DurationDeserializer;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

public record ExecutorInfo(
        String executorId,
        String executorType,
        CommunicationType communicationType,
        String endpoint,
        @JsonSerialize(using = DurationSerializer.class)
        @JsonDeserialize(using = DurationDeserializer.class)
        Duration timeout,
        Map<String, String> metadata) {

    @JsonCreator
    public ExecutorInfo(
            @JsonProperty("executorId") String executorId,
            @JsonProperty("executorType") String executorType,
            @JsonProperty("communicationType") CommunicationType communicationType,
            @JsonProperty("endpoint") String endpoint,
            @JsonProperty("timeout") Duration timeout,
            @JsonProperty("metadata") Map<String, String> metadata) {
        this.executorId = requireText(executorId, "executorId");
        this.executorType = requireText(executorType, "executorType");
        this.communicationType = requireCommunicationType(communicationType);
        this.endpoint = endpoint != null ? endpoint.trim() : null;
        this.timeout = validateTimeout(timeout);
        this.metadata = immutableMetadata(metadata);
    }

    public ExecutorInfo withEndpoint(String endpoint) {
        return new ExecutorInfo(executorId, executorType, communicationType, endpoint, timeout, metadata);
    }

    public ExecutorInfo withMetadata(Map<String, String> metadata) {
        return new ExecutorInfo(executorId, executorType, communicationType, endpoint, timeout, metadata);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value.trim();
    }

    private static CommunicationType requireCommunicationType(CommunicationType communicationType) {
        if (communicationType == null || communicationType == CommunicationType.UNSPECIFIED) {
            throw invalid("communicationType must be specified");
        }
        return communicationType;
    }

    private static Duration validateTimeout(Duration timeout) {
        if (timeout != null && timeout.isNegative()) {
            throw invalid("timeout cannot be negative");
        }
        return timeout;
    }

    private static Map<String, String> immutableMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw invalid("metadata keys cannot be null or blank");
            }
            if (value == null) {
                throw invalid("metadata value for key '" + key + "' cannot be null");
            }
        });
        return ExecutionPayloads.immutableMapCopy(metadata);
    }

    private static GamelanException invalid(String message) {
        return new GamelanException(ErrorCode.VALIDATION_FAILED, "Invalid executor info: " + message);
    }
}
