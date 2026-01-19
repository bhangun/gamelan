package tech.kayys.gamelan.engine.executor;

public interface ExecutorClientFactory {

    ExecutorClient forNodeType(String nodeType);
}
