package tech.kayys.gamelan.core.executor;

import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.executor.ExecutorClientFactory;
import tech.kayys.gamelan.engine.executor.ExecutorDispatcher;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeResult;

@ApplicationScoped
public class RpcExecutorDispatcher implements ExecutorDispatcher {

    @Inject
    ExecutorClientFactory clientFactory;

    @Override
    public CompletionStage<NodeResult> dispatch(
            NodeContext node,
            NodeExecutionContext ctx) {

        ExecutorClient client = clientFactory
                .forNodeType(node.nodeType());

        return client.execute(node, ctx.workflow().variables());
    }
}
