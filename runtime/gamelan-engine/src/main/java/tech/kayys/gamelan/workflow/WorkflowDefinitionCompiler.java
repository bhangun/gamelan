package tech.kayys.gamelan.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;

/**
 * Bounded compiler/cache for immutable workflow definition topology.
 */
@ApplicationScoped
public class WorkflowDefinitionCompiler {

    private static final int DEFAULT_CACHE_MAX_SIZE = 1024;

    @ConfigProperty(name = "gamelan.workflow.compilation.cache.enabled", defaultValue = "true")
    boolean cacheEnabled = true;

    @ConfigProperty(name = "gamelan.workflow.compilation.cache.max-size", defaultValue = "1024")
    int cacheMaxSize = DEFAULT_CACHE_MAX_SIZE;

    @Inject
    MeterRegistry meterRegistry;

    private final Map<CacheKey, CompiledWorkflowDefinition> cache = new LinkedHashMap<>(16, 0.75f, true);
    private volatile CompilerMetrics compilerMetrics;

    public CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "WorkflowDefinition cannot be null");
        CompilerMetrics metrics = compilerMetrics();
        if (!cacheEnabled) {
            metrics.recordBypass();
            Timer.Sample sample = metrics.startCompile();
            try {
                return CompiledWorkflowDefinition.compile(definition);
            } finally {
                metrics.recordCompileDuration(sample, CompilerMetrics.SOURCE_BYPASS);
            }
        }

        CacheKey key = CacheKey.from(definition);
        synchronized (cache) {
            CompiledWorkflowDefinition cached = cache.get(key);
            if (cached != null) {
                metrics.recordHit();
                return cached;
            }
        }

        metrics.recordMiss();
        Timer.Sample sample = metrics.startCompile();
        CompiledWorkflowDefinition compiled;
        try {
            compiled = CompiledWorkflowDefinition.compile(definition);
        } finally {
            metrics.recordCompileDuration(sample, CompilerMetrics.SOURCE_CACHE_MISS);
        }

        int evictions;
        synchronized (cache) {
            CompiledWorkflowDefinition cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            cache.put(key, compiled);
            evictions = evictOverflow();
        }
        metrics.recordEvictions(evictions);
        return compiled;
    }

    public int invalidate(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "WorkflowDefinition cannot be null");
        return invalidate(definition.id(), definition.tenantId());
    }

    public int invalidate(WorkflowDefinitionId definitionId, TenantId tenantId) {
        Objects.requireNonNull(definitionId, "Workflow definition ID cannot be null");
        Objects.requireNonNull(tenantId, "Tenant ID cannot be null");

        int removed;
        synchronized (cache) {
            removed = removeEntries(definitionId, tenantId);
        }
        compilerMetrics().recordInvalidations(removed);
        return removed;
    }

    int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    int clear() {
        int removed;
        synchronized (cache) {
            removed = cache.size();
            cache.clear();
        }
        compilerMetrics().recordInvalidations(removed);
        return removed;
    }

    private int removeEntries(WorkflowDefinitionId definitionId, TenantId tenantId) {
        int removed = 0;
        var iterator = cache.keySet().iterator();
        while (iterator.hasNext()) {
            CacheKey key = iterator.next();
            if (key.matches(definitionId, tenantId)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private int evictOverflow() {
        int maxSize = effectiveCacheMaxSize();
        int evictions = 0;
        while (cache.size() > maxSize) {
            CacheKey eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
            evictions++;
        }
        return evictions;
    }

    private int effectiveCacheMaxSize() {
        return cacheMaxSize > 0 ? cacheMaxSize : DEFAULT_CACHE_MAX_SIZE;
    }

    private CompilerMetrics compilerMetrics() {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            return CompilerMetrics.NOOP;
        }

        CompilerMetrics current = compilerMetrics;
        if (current == null || current.registry != registry) {
            synchronized (this) {
                current = compilerMetrics;
                if (current == null || current.registry != registry) {
                    current = new CompilerMetrics(registry, this);
                    compilerMetrics = current;
                }
            }
        }
        return current;
    }

    private record CacheKey(
            String tenantId,
            String definitionId,
            String version,
            int fingerprint) {

        private static CacheKey from(WorkflowDefinition definition) {
            return new CacheKey(
                    definition.tenantId().value(),
                    definition.id().value(),
                    definition.version(),
                    definition.hashCode());
        }

        private boolean matches(WorkflowDefinitionId definitionId, TenantId tenantId) {
            return this.tenantId.equals(tenantId.value())
                    && this.definitionId.equals(definitionId.value());
        }
    }

    private static final class CompilerMetrics {
        private static final String SOURCE_BYPASS = "bypass";
        private static final String SOURCE_CACHE_MISS = "cache_miss";

        private static final CompilerMetrics NOOP = new CompilerMetrics();

        private final MeterRegistry registry;
        private final Counter hitCounter;
        private final Counter missCounter;
        private final Counter bypassCounter;
        private final Counter evictionCounter;
        private final Counter invalidationCounter;
        private final Timer bypassCompileTimer;
        private final Timer cacheMissCompileTimer;

        private CompilerMetrics() {
            this.registry = null;
            this.hitCounter = null;
            this.missCounter = null;
            this.bypassCounter = null;
            this.evictionCounter = null;
            this.invalidationCounter = null;
            this.bypassCompileTimer = null;
            this.cacheMissCompileTimer = null;
        }

        private CompilerMetrics(MeterRegistry registry, WorkflowDefinitionCompiler compiler) {
            this.registry = registry;
            this.hitCounter = requestCounter(registry, "hit");
            this.missCounter = requestCounter(registry, "miss");
            this.bypassCounter = requestCounter(registry, "bypass");
            this.evictionCounter = Counter.builder("gamelan.workflow.compilation.cache.evictions")
                    .description("Workflow definition compilation cache evictions")
                    .register(registry);
            this.invalidationCounter = Counter.builder("gamelan.workflow.compilation.cache.invalidations")
                    .description("Workflow definition compilation cache entries explicitly invalidated")
                    .register(registry);
            this.bypassCompileTimer = compileTimer(registry, SOURCE_BYPASS);
            this.cacheMissCompileTimer = compileTimer(registry, SOURCE_CACHE_MISS);
            Gauge.builder("gamelan.workflow.compilation.cache.size", compiler, WorkflowDefinitionCompiler::cacheSize)
                    .description("Current workflow definition compilation cache size")
                    .register(registry);
        }

        private void recordHit() {
            increment(hitCounter);
        }

        private void recordMiss() {
            increment(missCounter);
        }

        private void recordBypass() {
            increment(bypassCounter);
        }

        private void recordEvictions(int evictions) {
            if (evictionCounter != null && evictions > 0) {
                evictionCounter.increment(evictions);
            }
        }

        private void recordInvalidations(int invalidations) {
            if (invalidationCounter != null && invalidations > 0) {
                invalidationCounter.increment(invalidations);
            }
        }

        private Timer.Sample startCompile() {
            return registry != null ? Timer.start(registry) : null;
        }

        private void recordCompileDuration(Timer.Sample sample, String source) {
            Timer timer = SOURCE_BYPASS.equals(source) ? bypassCompileTimer : cacheMissCompileTimer;
            if (sample != null && timer != null) {
                sample.stop(timer);
            }
        }

        private static Counter requestCounter(MeterRegistry registry, String result) {
            return Counter.builder("gamelan.workflow.compilation.cache.requests")
                    .description("Workflow definition compilation cache requests")
                    .tag("result", result)
                    .register(registry);
        }

        private static Timer compileTimer(MeterRegistry registry, String source) {
            return Timer.builder("gamelan.workflow.compilation.duration")
                    .description("Workflow definition compilation duration")
                    .tag("source", source)
                    .register(registry);
        }

        private static void increment(Counter counter) {
            if (counter != null) {
                counter.increment();
            }
        }
    }
}
