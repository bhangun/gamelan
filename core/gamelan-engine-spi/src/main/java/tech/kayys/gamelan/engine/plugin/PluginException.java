package tech.kayys.gamelan.engine.plugin;

/**
 * Exception thrown by plugin operations
 */
public class PluginException extends Exception {

    private final String pluginId;

    public PluginException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public PluginException(String pluginId, String message, Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
    }

    public PluginException(String pluginId, Throwable cause) {
        super(cause);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
