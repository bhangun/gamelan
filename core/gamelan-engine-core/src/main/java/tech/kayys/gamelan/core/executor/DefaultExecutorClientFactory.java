package tech.kayys.gamelan.core.executor;

import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.ConfigProvider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.executor.ExecutorClientFactory;

@ApplicationScoped
public class DefaultExecutorClientFactory implements ExecutorClientFactory {

    private final Map<String, ExecutorClient> clients;

    @Inject
    public DefaultExecutorClientFactory(
            Instance<ExecutorClient> executorClients,
            Configuration config) {
        this.clients = executorClients.stream()
                .collect(Collectors.toMap(
                        ExecutorClient::executorType,
                        c -> c));
    }

    @Override
    public ExecutorClient forNodeType(String nodeType) {

        // configurable mapping
        String executorType = ConfigProvider.getConfig()
                .getValue("executor.mapping." + nodeType, String.class);

        ExecutorClient client = clients.get(executorType);

        if (client == null) {
            throw new IllegalStateException(
                    "No executor for nodeType=" + nodeType);
        }

        return client;
    }
}
