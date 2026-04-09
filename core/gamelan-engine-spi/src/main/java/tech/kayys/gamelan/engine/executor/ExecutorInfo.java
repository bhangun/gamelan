package tech.kayys.gamelan.engine.executor;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.DurationSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.DurationDeserializer;

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
        this.executorId = executorId;
        this.executorType = executorType;
        this.communicationType = communicationType;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.metadata = metadata;
    }
}
