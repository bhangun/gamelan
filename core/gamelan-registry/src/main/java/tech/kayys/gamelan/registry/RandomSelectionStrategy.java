package tech.kayys.gamelan.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Random executor selection strategy
 */
public class RandomSelectionStrategy implements ExecutorSelectionStrategy {

    private final Random random = new Random();

    @Override
    public Optional<ExecutorInfo> select(NodeId nodeId, List<ExecutorInfo> availableExecutors,
            Map<String, Object> context) {
        if (availableExecutors.isEmpty()) {
            return Optional.empty();
        }

        int randomIndex = random.nextInt(availableExecutors.size());
        return Optional.of(availableExecutors.get(randomIndex));
    }

    @Override
    public String getName() {
        return "random";
    }
}
