package tech.kayys.silat.grpc;

import tech.kayys.silat.grpc.v1.CommunicationType;

/**
 * Utility class to convert between internal CommunicationType and gRPC
 * CommunicationType
 */
public class CommunicationTypeConverter {

    /**
     * Convert internal CommunicationType to gRPC CommunicationType
     */
    public static CommunicationType toGrpc(tech.kayys.silat.model.CommunicationType internalType) {
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
    public static tech.kayys.silat.model.CommunicationType fromGrpc(CommunicationType grpcType) {
        if (grpcType == null) {
            return tech.kayys.silat.model.CommunicationType.UNSPECIFIED;
        }
        switch (grpcType) {
            case COMMUNICATION_TYPE_GRPC:
                return tech.kayys.silat.model.CommunicationType.GRPC;
            case COMMUNICATION_TYPE_KAFKA:
                return tech.kayys.silat.model.CommunicationType.KAFKA;
            case COMMUNICATION_TYPE_REST:
                return tech.kayys.silat.model.CommunicationType.REST;
            case COMMUNICATION_TYPE_LOCAL:
                return tech.kayys.silat.model.CommunicationType.LOCAL;
            default:
                return tech.kayys.silat.model.CommunicationType.UNSPECIFIED;
        }
    }
}
