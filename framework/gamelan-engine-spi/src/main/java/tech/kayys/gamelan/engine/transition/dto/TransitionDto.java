package tech.kayys.gamelan.engine.transition.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import tech.kayys.gamelan.engine.run.Transition;

/**
 * Transition DTO
 */
@Schema(description = "Transition definition")
public record TransitionDto(
        @NotBlank @Schema(description = "Target node ID", required = true) String targetNodeId,

        @Schema(description = "Condition expression") String condition,

        @Schema(description = "Transition type") String type) {
    public static TransitionDto from(Transition transition) {
        return new TransitionDto(
                transition.targetNodeId().value(),
                transition.condition(),
                transition.type().name());
    }
}
