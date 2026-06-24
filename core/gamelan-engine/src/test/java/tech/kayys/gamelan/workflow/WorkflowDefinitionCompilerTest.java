package tech.kayys.gamelan.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;

class WorkflowDefinitionCompilerTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    @Test
    void compile_ordersDagNodesTopologically() {
        NodeDefinition child = node("child", "parent");
        NodeDefinition parent = node("parent");
        WorkflowDefinition definition = workflow("wf-topology", WorkflowMode.DAG, List.of(child, parent));

        CompiledWorkflowDefinition compiled = CompiledWorkflowDefinition.compile(definition);

        assertEquals(List.of(parent, child), compiled.orderedNodes());
        assertEquals(List.of(parent.id()), compiled.dependencies(child.id()));
        assertEquals(List.of(child.id()), compiled.dependentsByNode().get(parent.id()));
        assertEquals(List.of(parent.id()), compiled.startNodeIds());
    }

    @Test
    void compile_preservesOriginalOrderWhenDagHasUnknownDependency() {
        NodeDefinition blocked = node("blocked", "missing");
        NodeDefinition start = node("start");
        WorkflowDefinition definition = workflow("wf-invalid", WorkflowMode.DAG, List.of(blocked, start));

        CompiledWorkflowDefinition compiled = CompiledWorkflowDefinition.compile(definition);

        assertEquals(List.of(blocked, start), compiled.orderedNodes());
        assertEquals(List.of(NodeId.of("missing")), compiled.dependencies(blocked.id()));
    }

    @Test
    void compile_usesBoundedLruCacheWhenEnabled() {
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler();
        compiler.cacheEnabled = true;
        compiler.cacheMaxSize = 2;
        WorkflowDefinition first = workflow("wf-1", WorkflowMode.FLOW, List.of(node("start-1")));
        WorkflowDefinition second = workflow("wf-2", WorkflowMode.FLOW, List.of(node("start-2")));
        WorkflowDefinition third = workflow("wf-3", WorkflowMode.FLOW, List.of(node("start-3")));

        CompiledWorkflowDefinition firstCompile = compiler.compile(first);
        CompiledWorkflowDefinition firstCached = compiler.compile(first);
        compiler.compile(second);
        compiler.compile(third);

        assertSame(firstCompile, firstCached);
        assertEquals(2, compiler.cacheSize());
        assertTrue(compiler.compile(first) != firstCompile);
        assertEquals(2, compiler.cacheSize());
    }

    @Test
    void compile_recordsCacheMetricsWhenEnabled() {
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler();
        compiler.cacheEnabled = true;
        compiler.cacheMaxSize = 2;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        compiler.meterRegistry = meterRegistry;
        WorkflowDefinition first = workflow("wf-metrics-1", WorkflowMode.FLOW, List.of(node("start-1")));
        WorkflowDefinition second = workflow("wf-metrics-2", WorkflowMode.FLOW, List.of(node("start-2")));
        WorkflowDefinition third = workflow("wf-metrics-3", WorkflowMode.FLOW, List.of(node("start-3")));

        compiler.compile(first);
        compiler.compile(first);
        compiler.compile(second);
        compiler.compile(third);

        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.requests", "result", "hit"));
        assertEquals(3.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.requests", "result", "miss"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.requests", "result", "bypass"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.evictions"));
        assertEquals(3, timerCount(meterRegistry, "gamelan.workflow.compilation.duration", "source", "cache_miss"));
        assertEquals(0, timerCount(meterRegistry, "gamelan.workflow.compilation.duration", "source", "bypass"));
        assertEquals(2.0, gauge(meterRegistry, "gamelan.workflow.compilation.cache.size"));
    }

    @Test
    void invalidate_removesAllCachedFingerprintsForDefinition() {
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler();
        compiler.cacheEnabled = true;
        compiler.cacheMaxSize = 10;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        compiler.meterRegistry = meterRegistry;
        WorkflowDefinition firstVersion = workflow("wf-invalidate", "1.0.0", WorkflowMode.FLOW, List.of(node("start")));
        WorkflowDefinition secondVersion = workflow("wf-invalidate", "2.0.0", WorkflowMode.FLOW, List.of(node("start")));
        WorkflowDefinition other = workflow("wf-other", "1.0.0", WorkflowMode.FLOW, List.of(node("other-start")));

        compiler.compile(firstVersion);
        compiler.compile(secondVersion);
        compiler.compile(other);

        assertEquals(2, compiler.invalidate(firstVersion.id(), TENANT));
        assertEquals(1, compiler.cacheSize());
        assertEquals(2.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.invalidations"));
        assertEquals(0, compiler.invalidate(WorkflowDefinitionId.of("missing"), TENANT));
        assertEquals(2.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.invalidations"));
    }

    @Test
    void compile_bypassesCacheWhenDisabled() {
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler();
        compiler.cacheEnabled = false;
        WorkflowDefinition definition = workflow("wf-no-cache", WorkflowMode.FLOW, List.of(node("start")));

        CompiledWorkflowDefinition first = compiler.compile(definition);
        CompiledWorkflowDefinition second = compiler.compile(definition);

        assertTrue(first != second);
        assertEquals(0, compiler.cacheSize());
    }

    @Test
    void compile_recordsBypassMetricsWhenCacheDisabled() {
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler();
        compiler.cacheEnabled = false;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        compiler.meterRegistry = meterRegistry;
        WorkflowDefinition definition = workflow("wf-bypass-metrics", WorkflowMode.FLOW, List.of(node("start")));

        compiler.compile(definition);
        compiler.compile(definition);

        assertEquals(2.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.requests", "result", "bypass"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.requests", "result", "hit"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.workflow.compilation.cache.requests", "result", "miss"));
        assertEquals(2, timerCount(meterRegistry, "gamelan.workflow.compilation.duration", "source", "bypass"));
        assertEquals(0.0, gauge(meterRegistry, "gamelan.workflow.compilation.cache.size"));
    }

    private static WorkflowDefinition workflow(
            String id,
            WorkflowMode mode,
            List<NodeDefinition> nodes) {
        return workflow(id, "1.0.0", mode, nodes);
    }

    private static WorkflowDefinition workflow(
            String id,
            String version,
            WorkflowMode mode,
            List<NodeDefinition> nodes) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(id),
                TENANT,
                id,
                version,
                null,
                mode,
                nodes,
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node(String id, String... dependencies) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                java.util.Arrays.stream(dependencies).map(NodeId::of).toList(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private static long timerCount(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0;
    }

    private static double gauge(SimpleMeterRegistry meterRegistry, String name) {
        var gauge = meterRegistry.find(name).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }
}
