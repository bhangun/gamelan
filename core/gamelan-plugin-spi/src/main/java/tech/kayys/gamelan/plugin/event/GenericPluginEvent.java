package tech.kayys.gamelan.plugin.event;

import java.util.Map;
import tech.kayys.gamelan.engine.plugin.PluginEvent;

/**
 * Generic event for arbitrary payloads
 */
public class GenericPluginEvent extends PluginEvent {

    private final String type;
    private final Object payload;

    public GenericPluginEvent(String sourcePluginId, String type, Object payload, Map<String, Object> metadata) {
        super(sourcePluginId, metadata);
        this.type = type;
        this.payload = payload;
    }

    public String getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }
}
