package tech.kayys.gamelan.engine.extension;

import tech.kayys.gamelan.engine.context.EngineContext;

public interface EngineExtension {
    default void onEngineStart(EngineContext ctx) {
    }

    default void onEngineStop() {
    }
}