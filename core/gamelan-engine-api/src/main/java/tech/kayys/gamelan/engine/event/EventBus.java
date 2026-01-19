package tech.kayys.gamelan.engine.event;

import java.util.function.Consumer;

import tech.kayys.gamelan.engine.plugin.PluginEvent;

/**
 * Event bus for plugin event communication
 * 
 * Plugins can publish and subscribe to events.
 */
public interface EventBus {

    /**
     * Publish an event
     * 
     * @param event the event to publish
     */
    void publish(PluginEvent event);

    /**
     * Subscribe to events of a specific type
     * 
     * @param eventType the event type
     * @param handler   the event handler
     * @param <T>       the event type
     * @return a subscription that can be used to unsubscribe
     */
    <T extends PluginEvent> Subscription subscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * Subscription handle
     */
    interface Subscription {
        /**
         * Unsubscribe from events
         */
        void unsubscribe();
    }
}
