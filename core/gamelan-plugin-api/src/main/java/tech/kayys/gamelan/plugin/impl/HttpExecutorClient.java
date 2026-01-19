package tech.kayys.gamelan.plugin.impl;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;
import io.vertx.mutiny.ext.web.client.WebClient;

@ApplicationScoped
public class HttpExecutorClient implements ExecutorClient {

    @Inject
    WebClient client;

    @Override
    public String executorType() {
        return "http";
    }

    @Override
    public CompletionStage<NodeResult> execute(
            NodeContext node,
            Map<String, Object> vars) {

        return client.post("/execute")
                .sendJson(Map.of(
                        "node", node,
                        "variables", vars))
                .map(resp -> resp.bodyAsJson(NodeResult.class))
                .subscribe().asCompletionStage();
    }
}
