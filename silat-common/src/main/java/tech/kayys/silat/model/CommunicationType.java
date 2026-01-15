package tech.kayys.silat.model;

/**
 * Communication Type enum for internal use
 * Aligns with the gRPC CommunicationType enum defined in silat.proto
 */
public enum CommunicationType {
    GRPC,
    KAFKA,
    REST,
    LOCAL,
    UNSPECIFIED
}
