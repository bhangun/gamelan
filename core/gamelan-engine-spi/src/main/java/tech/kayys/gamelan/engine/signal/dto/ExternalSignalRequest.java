package tech.kayys.gamelan.engine.signal.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.signal.ExternalSignal;

/**
 * HTTP/API DTO for external callback and webhook signals.
 */
public record ExternalSignalRequest(
        @NotBlank String signalType,
        @NotBlank String targetNodeId,
        String source,
        Map<String, Object> payload,
        Instant timestamp,
        String signature) implements ExternalSignal {

    @JsonCreator
    public ExternalSignalRequest(
            @JsonProperty("signalType") String signalType,
            @JsonProperty("targetNodeId") String targetNodeId,
            @JsonProperty("source") String source,
            @JsonProperty("payload") Map<String, Object> payload,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("signature") String signature) {
        this.signalType = signalType;
        this.targetNodeId = targetNodeId;
        this.source = source;
        this.payload = ExecutionPayloads.immutableMap(payload);
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.signature = signature;
    }

    @Override
    public String getSignalType() {
        return signalType;
    }

    @Override
    public NodeId getTargetNodeId() {
        return NodeId.of(targetNodeId);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public Map<String, Object> getPayload() {
        return payload;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String getSignature() {
        return signature;
    }
}
