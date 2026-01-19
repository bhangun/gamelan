package tech.kayys.gamelan.grpc;

import tech.kayys.gamelan.grpc.v1.CommunicationType;

/**
 * Utility class to convert between internal CommunicationType and gRPC
 * CommunicationType
 */
public class CommunicationTypeConverter {

    /**
     * Convert internal CommunicationType to gRPC CommunicationType
     */
    public static CommunicationType toGrpc(tech.kayys.gamelan.engine.protocol.CommunicationType internalType) {
        if (internalType == null) {
            return CommunicationType.COMMUNICATION_TYPE_UNSPECIFIED;
        }
        switch (internalType) {
            case GRPC:
                return CommunicationType.COMMUNICATION_TYPE_GRPC;
            case KAFKA:
                return CommunicationType.COMMUNICATION_TYPE_KAFKA;
            case REST:
                return CommunicationType.COMMUNICATION_TYPE_REST;
            case LOCAL:
                return CommunicationType.COMMUNICATION_TYPE_LOCAL;
            default:
                return CommunicationType.COMMUNICATION_TYPE_UNSPECIFIED;
        }
    }

    /**
     * Convert gRPC CommunicationType to internal CommunicationType
     */
    public static tech.kayys.gamelan.engine.protocol.CommunicationType fromGrpc(CommunicationType grpcType) {
        if (grpcType == null) {
            return tech.kayys.gamelan.engine.protocol.CommunicationType.UNSPECIFIED;
        }
        switch (grpcType) {
            case COMMUNICATION_TYPE_GRPC:
                return tech.kayys.gamelan.engine.protocol.CommunicationType.GRPC;
            case COMMUNICATION_TYPE_KAFKA:
                return tech.kayys.gamelan.engine.protocol.CommunicationType.KAFKA;
            case COMMUNICATION_TYPE_REST:
                return tech.kayys.gamelan.engine.protocol.CommunicationType.REST;
            case COMMUNICATION_TYPE_LOCAL:
                return tech.kayys.gamelan.engine.protocol.CommunicationType.LOCAL;
            default:
                return tech.kayys.gamelan.engine.protocol.CommunicationType.UNSPECIFIED;
        }
    }
}
