package tech.kayys.gamelan.engine.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for PluginMetadata
 */
public class PluginMetadataBuilder {
    private String id;
    private String name;
    private String version;
    private String author;
    private String description;
    private List<PluginMetadata.PluginDependency> dependencies = new ArrayList<>();
    private Map<String, String> properties = new HashMap<>();

    public static PluginMetadataBuilder builder() {
        return new PluginMetadataBuilder();
    }

    public PluginMetadataBuilder id(String id) {
        this.id = id;
        return this;
    }

    public PluginMetadataBuilder name(String name) {
        this.name = name;
        return this;
    }

    public PluginMetadataBuilder version(String version) {
        this.version = version;
        return this;
    }

    public PluginMetadataBuilder author(String author) {
        this.author = author;
        return this;
    }

    public PluginMetadataBuilder description(String description) {
        this.description = description;
        return this;
    }

    public PluginMetadataBuilder addDependency(String pluginId, String versionConstraint) {
        this.dependencies.add(new PluginMetadata.PluginDependency(pluginId, versionConstraint));
        return this;
    }

    public PluginMetadataBuilder property(String key, String value) {
        this.properties.put(key, value);
        return this;
    }

    public PluginMetadata build() {
        return new PluginMetadata(id, name, version, author, description, dependencies, properties);
    }
}