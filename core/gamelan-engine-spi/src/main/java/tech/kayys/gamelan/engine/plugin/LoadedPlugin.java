package tech.kayys.gamelan.engine.plugin;

public record LoadedPlugin(
                GamelanPlugin plugin,
                PluginState state) {
}