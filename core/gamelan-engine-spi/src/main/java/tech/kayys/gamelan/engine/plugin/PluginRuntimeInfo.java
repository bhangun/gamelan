package tech.kayys.gamelan.engine.plugin;

public record PluginRuntimeInfo(
                String runtimeMode, // standalone | distributed | executor
                SemVer engineVersion) {
}