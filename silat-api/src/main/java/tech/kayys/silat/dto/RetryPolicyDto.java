package tech.kayys.silat.dto;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import tech.kayys.silat.model.RetryPolicy;

/**
 * Retry policy DTO
 */
@Schema(description = "Retry policy")
public record RetryPolicyDto(
    @Min(1) @Max(10)
    @Schema(description = "Max attempts", minimum = "1", maximum = "10")
    int maxAttempts,
    
    @Min(0)
    @Schema(description = "Initial delay in seconds")
    long initialDelaySeconds,
    
    @Min(0)
    @Schema(description = "Max delay in seconds")
    long maxDelaySeconds,
    
    @DecimalMin("1.0") @DecimalMax("10.0")
    @Schema(description = "Backoff multiplier")
    double backoffMultiplier,
    
    @Schema(description = "Retryable exceptions")
    List<String> retryableExceptions
) {
    public static RetryPolicyDto from(RetryPolicy policy) {
        return new RetryPolicyDto(
            policy.maxAttempts(),
            policy.initialDelay().toSeconds(),
            policy.maxDelay().toSeconds(),
            policy.backoffMultiplier(),
            policy.retryableExceptions()
        );
    }
}
