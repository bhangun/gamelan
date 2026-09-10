package tech.kayys.gamelan.engine.saga.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import tech.kayys.gamelan.engine.saga.CompensationPolicy;

/**
 * Compensation policy DTO
 */
@Schema(description = "Compensation policy")
public record CompensationPolicyDto(
        @Schema(description = "Strategy") String strategy,

        @Schema(description = "Timeout in seconds") long timeoutSeconds,

        @Schema(description = "Fail on compensation error") boolean failOnCompensationError) {
    public static CompensationPolicyDto from(CompensationPolicy policy) {
        return new CompensationPolicyDto(
                policy.strategy().name(),
                policy.timeout().toSeconds(),
                policy.failOnCompensationError());
    }
}
