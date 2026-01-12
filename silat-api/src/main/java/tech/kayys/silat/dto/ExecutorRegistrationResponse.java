package tech.kayys.silat.dto;

import java.time.Instant;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Executor registration response
 */
@Schema(description = "Executor registration response")
public record ExecutorRegistrationResponse(
    @Schema(description = "Executor ID")
    String executorId,
    
    @Schema(description = "Status")
    String status,
    
    @Schema(description = "Registered timestamp")
    Instant registeredAt
) {}
