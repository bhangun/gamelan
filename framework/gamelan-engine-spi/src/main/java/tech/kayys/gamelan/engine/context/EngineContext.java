package tech.kayys.gamelan.engine.context;

import java.time.Clock;
import java.util.Map;

import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.executor.ExecutorClientFactory;
import tech.kayys.gamelan.engine.executor.ExecutorDispatcher;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.plugin.PluginRegistry;

public interface EngineContext {

    Clock clock();

    PluginRegistry pluginRegistry();

    ExecutorDispatcher executorDispatcher();

    EventBus eventBus();

    PersistenceProvider persistence();

    SecurityContext security();

    <T> T getService(Class<T> type);

    Map<String, Object> attributes();

    EventPublisher eventPublisher();

    Configuration configuration();

    ExecutorClientFactory executorClientFactory();

}
