package tech.kayys.gamelan.engine.signal;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeId;

/**
 * 🔒 External signal from outside services
 */
public interface ExternalSignal {

    String getSignalType(); // "callback", "webhook", "timer", "human_approval"

    NodeId getTargetNodeId();

    String getSource(); // External service identifier

    Map<String, Object> getPayload();

    Instant getTimestamp();

    String getSignature(); // For verification
}

/**
 * External Signal - Signal from external system
 */
/*
 * public record ExternalSignal(
 * String name,
 * NodeId targetNodeId,
 * Map<String, Object> payload) {
 * }
 */
