package tech.kayys.gamelan.core.engine;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EngineBootstrap {

    @Inject
    DefaultEngineContext engineContext;

    @Inject
    WorkflowEngine engine;

    @PostConstruct
    void init() {
        engine.initialize(engineContext);
    }
}
