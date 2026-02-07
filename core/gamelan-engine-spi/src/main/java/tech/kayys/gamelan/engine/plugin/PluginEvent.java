package tech.kayys.gamelan.engine.plugin;

import java.time.Instant;
import java.util.Map;

/**
 * Base class for plugin events
 */
public abstract class PluginEvent {

    private final String eventId;
    private final String sourcePluginId;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    protected PluginEvent(String sourcePluginId, Map<String, Object> metadata) {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.sourcePluginId = sourcePluginId;
        this.timestamp = Instant.now();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public String getEventId() {
        return eventId;
    }

    public String getSourcePluginId() {
        return sourcePluginId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
