package tech.kayys.gamelan.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Kafka serializers and deserializers
 */
@ApplicationScoped
public class WorkflowEventSerde implements org.apache.kafka.common.serialization.Serde<WorkflowEventMessage> {

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public org.apache.kafka.common.serialization.Serializer<WorkflowEventMessage> serializer() {
        return (topic, data) -> {
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize", e);
            }
        };
    }

    @Override
    public org.apache.kafka.common.serialization.Deserializer<WorkflowEventMessage> deserializer() {
        return (topic, data) -> {
            try {
                return objectMapper.readValue(data, WorkflowEventMessage.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize", e);
            }
        };
    }
}
