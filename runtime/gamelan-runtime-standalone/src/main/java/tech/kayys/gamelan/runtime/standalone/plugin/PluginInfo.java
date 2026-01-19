package tech.kayys.gamelan.runtime.standalone.plugin;

import java.net.URLClassLoader;

/**
 * Represents information about a loaded plugin
 */
public class PluginInfo {
    private final String name;
    private final String version;
    private final String fileName;
    private final String filePath;
    private final Class<?> pluginClass;
    private final URLClassLoader classLoader;
    private boolean enabled;

    public PluginInfo(String name, String version, String fileName, String filePath, 
                      Class<?> pluginClass, URLClassLoader classLoader, boolean enabled) {
        this.name = name;
        this.version = version;
        this.fileName = fileName;
        this.filePath = filePath;
        this.pluginClass = pluginClass;
        this.classLoader = classLoader;
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public Class<?> getPluginClass() {
        return pluginClass;
    }

    public URLClassLoader getClassLoader() {
        return classLoader;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}