package tech.kayys.gamelan.engine.protocol;

/**
 * Communication Type enum for internal use
 * Aligns with the gRPC CommunicationType enum defined in gamelan.proto
 */
public enum CommunicationType {
    GRPC,
    KAFKA,
    REST,
    LOCAL,
    UNSPECIFIED
}
