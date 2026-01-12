package tech.kayys.silat.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * Output definition DTO
 */
@Schema(description = "Output definition")
public record OutputDefinitionDto(
    @NotBlank
    @Schema(description = "Name", required = true)
    String name,
    
    @NotBlank
    @Schema(description = "Type", required = true)
    String type,
    
    @Schema(description = "Description")
    String description
) {}
