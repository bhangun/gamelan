package tech.kayys.gamelan.engine.plugin;

import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Plugin metadata containing information about a plugin
 */
public record PluginMetadata(
        String id,
        String name,
        String version,
        String author,
        String description,
        List<PluginDependency> dependencies,
        Map<String, String> properties) {
    public PluginMetadata {
        if (id == null || id.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Plugin ID cannot be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Plugin name cannot be null or blank");
        }
        if (version == null || version.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Plugin version cannot be null or blank");
        }
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }

    /**
     * Plugin dependency information
     */
    public record PluginDependency(
            String pluginId,
            String versionConstraint) {
        public PluginDependency {
            if (pluginId == null || pluginId.isBlank()) {
                throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Plugin dependency ID cannot be null or blank");
            }
            if (versionConstraint == null || versionConstraint.isBlank()) {
                throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Version constraint cannot be null or blank");
            }
        }
    }
}
