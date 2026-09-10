package tech.kayys.gamelan.runtime.resource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator;
import tech.kayys.gamelan.engine.event.EventPublisherDiagnostics;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisherDiagnostics;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext;

/**
 * Exposes runtime capability discovery, health, and readiness endpoints.
 */
@Path("/api/v1/runtime")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RuntimeCapabilitiesResource {

    @Inject
    RuntimeCapabilityInspector capabilityInspector;

    @ConfigProperty(name = "gamelan.runtime.capabilities.readiness.accept-degraded", defaultValue = "true")
    Boolean runtimeCapabilitiesReadinessAcceptDegraded;

    @ConfigProperty(name = "gamelan.runtime.capabilities.readiness.issue-detail-limit", defaultValue = "20")
    Integer runtimeCapabilitiesReadinessIssueDetailLimit;

    @ConfigProperty(name = "gamelan.runtime.capabilities.cache-ttl", defaultValue = "0s")
    Duration runtimeCapabilitiesCacheTtl;

    private volatile CachedRuntimeCapabilities cachedCapabilities;

    @GET
    @Path("/capabilities")
    public RuntimeCapabilities capabilities() {
        return capabilitiesSnapshot();
    }

    @GET
    @Path("/health")
    public RuntimeCapabilityHealth health() {
        return capabilitiesSnapshot().health();
    }

    @GET
    @Path("/readiness")
    public Response readiness() {
        RuntimeCapabilities snapshot = capabilitiesSnapshot();
        RuntimeCapabilityReadiness readiness =
                RuntimeCapabilityReadiness.from(
                        snapshot.health(),
                        runtimeCapabilityReadinessPolicy(),
                        runtimeCapabilitiesReadinessIssueDetailLimit,
                        snapshot.cache());
        return Response.status(readiness.ready() ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                .entity(readiness)
                .build();
    }

    private RuntimeCapabilities capabilitiesSnapshot() {
        RuntimeCapabilities cached = cachedCapabilitiesIfFresh(Instant.now());
        if (cached != null) {
            return cached;
        }
        return cacheIfEnabled(capabilityInspector.capabilities());
    }

    private RuntimeCapabilityReadinessPolicy runtimeCapabilityReadinessPolicy() {
        return new RuntimeCapabilityReadinessPolicy(runtimeCapabilitiesReadinessAcceptDegraded == null
                || Boolean.TRUE.equals(runtimeCapabilitiesReadinessAcceptDegraded));
    }

    private RuntimeCapabilities cachedCapabilitiesIfFresh(Instant now) {
        Duration ttl = effectiveCapabilitiesCacheTtl();
        if (ttl == null) {
            cachedCapabilities = null;
            return null;
        }
        CachedRuntimeCapabilities cached = cachedCapabilities;
        if (cached == null || !ttl.equals(cached.ttl()) || cached.expired(now)) {
            if (cachedCapabilities == cached) {
                cachedCapabilities = null;
            }
            return null;
        }
        return cached.capabilities().withCache(RuntimeCapabilityCache.hit(ttl, cached.cachedAt(), now));
    }

    private RuntimeCapabilities cacheIfEnabled(RuntimeCapabilities capabilities) {
        Duration ttl = effectiveCapabilitiesCacheTtl();
        if (ttl == null) {
            cachedCapabilities = null;
            return capabilities.withCache(RuntimeCapabilityCache.disabled());
        }
        Instant cachedAt = Instant.now();
        RuntimeCapabilities cacheMiss = capabilities.withCache(RuntimeCapabilityCache.miss(ttl, cachedAt));
        cachedCapabilities = new CachedRuntimeCapabilities(cacheMiss, cachedAt, ttl);
        return cacheMiss;
    }

    private Duration effectiveCapabilitiesCacheTtl() {
        if (runtimeCapabilitiesCacheTtl == null
                || runtimeCapabilitiesCacheTtl.isZero()
                || runtimeCapabilitiesCacheTtl.isNegative()) {
            return null;
        }
        return runtimeCapabilitiesCacheTtl;
    }

    public record RuntimeCapabilities(
            RuntimeProfile profile,
            RuntimeCapabilityContract contract,
            List<RuntimeComponent> components,
            List<RuntimeComponent> eventStores,
            List<RuntimeComponent> agentContextStores,
            List<RuntimeComponent> grpcTaskStreamBrokers,
            List<RuntimeComponent> recoveryLeaseRepositories,
            List<RuntimeComponent> wakeupOutboxes,
            List<ExecutorAdapterCapability> executorAdapters,
            List<TaskDispatcherAggregator.TaskDispatcherCapability> taskDispatchers,
            List<EventPublisherDiagnostics> eventPublishers,
            List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
            RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
            RuntimeCapabilityHealth health,
            RuntimeProbeConfig probes,
            RuntimeCapabilityCache cache,
            Instant observedAt) {

        public RuntimeCapabilities(
                RuntimeProfile profile,
                RuntimeCapabilityContract contract,
                List<RuntimeComponent> components,
                List<RuntimeComponent> eventStores,
                List<RuntimeComponent> agentContextStores,
                List<RuntimeComponent> grpcTaskStreamBrokers,
                List<RuntimeComponent> recoveryLeaseRepositories,
                List<RuntimeComponent> wakeupOutboxes,
                List<ExecutorAdapterCapability> executorAdapters,
                List<TaskDispatcherAggregator.TaskDispatcherCapability> taskDispatchers,
                List<EventPublisherDiagnostics> eventPublishers,
                List<WorkflowRunWakeupPublisherDiagnostics> wakeupPublishers,
                RuntimeExecutionContext.RuntimeExecutionStatus executionContext,
                RuntimeCapabilityHealth health,
                RuntimeProbeConfig probes,
                Instant observedAt) {
            this(
                    profile,
                    contract,
                    components,
                    eventStores,
                    agentContextStores,
                    grpcTaskStreamBrokers,
                    recoveryLeaseRepositories,
                    wakeupOutboxes,
                    executorAdapters,
                    taskDispatchers,
                    eventPublishers,
                    wakeupPublishers,
                    executionContext,
                    health,
                    probes,
                    RuntimeCapabilityCache.disabled(),
                    observedAt);
        }

        public RuntimeCapabilities {
            observedAt = observedAt != null ? observedAt : Instant.now();
            contract = contract != null
                    ? contract
                    : RuntimeCapabilityContracts.resolve("auto", profile);
            components = components == null ? List.of() : List.copyOf(components);
            eventStores = eventStores == null ? List.of() : List.copyOf(eventStores);
            agentContextStores = agentContextStores == null ? List.of() : List.copyOf(agentContextStores);
            grpcTaskStreamBrokers = grpcTaskStreamBrokers == null ? List.of() : List.copyOf(grpcTaskStreamBrokers);
            recoveryLeaseRepositories = recoveryLeaseRepositories == null
                    ? List.of()
                    : List.copyOf(recoveryLeaseRepositories);
            wakeupOutboxes = wakeupOutboxes == null ? List.of() : List.copyOf(wakeupOutboxes);
            executorAdapters = executorAdapters == null ? List.of() : List.copyOf(executorAdapters);
            taskDispatchers = taskDispatchers == null ? List.of() : List.copyOf(taskDispatchers);
            eventPublishers = eventPublishers == null ? List.of() : List.copyOf(eventPublishers);
            wakeupPublishers = wakeupPublishers == null ? List.of() : List.copyOf(wakeupPublishers);
            health = health != null ? health : RuntimeCapabilityHealth.fromIssues(List.of(), observedAt);
            cache = cache != null ? cache : RuntimeCapabilityCache.disabled();
        }

        private RuntimeCapabilities withCache(RuntimeCapabilityCache cache) {
            return new RuntimeCapabilities(
                    profile,
                    contract,
                    components,
                    eventStores,
                    agentContextStores,
                    grpcTaskStreamBrokers,
                    recoveryLeaseRepositories,
                    wakeupOutboxes,
                    executorAdapters,
                    taskDispatchers,
                    eventPublishers,
                    wakeupPublishers,
                    executionContext,
                    health,
                    probes,
                    cache,
                    observedAt);
        }
    }

    public record RuntimeProfile(
            String quarkusProfile,
            String workflowPersistenceStore,
            String agentContextStore,
            String registryPersistenceType,
            String registrySelectionStrategy,
            boolean registryPreferLocal,
            boolean grpcTaskStreamDefaultEnabled,
            String grpcTaskStreamBroker,
            String schedulerMode,
            String eventPublisherFamily,
            String wakeupOutboxStore,
            boolean recoveryDistributedLeaseEnabled) {
    }

    public record RuntimeComponent(
            String role,
            boolean available,
            String implementation) {
    }

    public record ExecutorAdapterCapability(
            String executorType,
            String implementation) {
    }

    public record RuntimeProbeConfig(
            long statusComponentTimeoutMillis,
            long statusCacheTtlMillis) {
    }

    /**
     * Readiness response with stable issue codes and bounded diagnostic details.
     */
    public record RuntimeCapabilityReadiness(
            boolean ready,
            RuntimeCapabilityHealth.Status status,
            List<String> issueCodes,
            List<RuntimeCapabilityHealth.Issue> issues,
            int totalIssueCount,
            int issueDetailLimit,
            boolean issueDetailsTruncated,
            RuntimeCapabilityCache cache,
            RuntimeCapabilityReadinessPolicy policy,
            String rejectionReason,
            Instant observedAt) {

        public RuntimeCapabilityReadiness {
            status = status != null ? status : RuntimeCapabilityHealth.Status.READY;
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
            issues = issues == null ? List.of() : List.copyOf(issues);
            issueDetailLimit = RuntimeCapabilityIssueDetailLimits.normalize(issueDetailLimit);
            totalIssueCount = Math.max(totalIssueCount, issues.size());
            issueDetailsTruncated = issueDetailsTruncated || totalIssueCount > issues.size();
            cache = cache != null ? cache : RuntimeCapabilityCache.disabled();
            policy = policy != null ? policy : new RuntimeCapabilityReadinessPolicy(true);
            observedAt = observedAt != null ? observedAt : Instant.now();
        }

        public static RuntimeCapabilityReadiness from(
                RuntimeCapabilityHealth health,
                RuntimeCapabilityReadinessPolicy policy) {
            return from(health, policy, RuntimeCapabilityIssueDetailLimits.DEFAULT_LIMIT);
        }

        public static RuntimeCapabilityReadiness from(
                RuntimeCapabilityHealth health,
                RuntimeCapabilityReadinessPolicy policy,
                Integer issueDetailLimit) {
            return from(health, policy, issueDetailLimit, RuntimeCapabilityCache.disabled());
        }

        public static RuntimeCapabilityReadiness from(
                RuntimeCapabilityHealth health,
                RuntimeCapabilityReadinessPolicy policy,
                Integer issueDetailLimit,
                RuntimeCapabilityCache cache) {
            RuntimeCapabilityHealth safeHealth = health != null
                    ? health
                    : RuntimeCapabilityHealth.fromIssues(List.of(), Instant.now());
            RuntimeCapabilityReadinessPolicy effectivePolicy =
                    policy != null ? policy : new RuntimeCapabilityReadinessPolicy(true);
            boolean ready = effectivePolicy.ready(safeHealth.status());
            int effectiveIssueDetailLimit = RuntimeCapabilityIssueDetailLimits.normalize(issueDetailLimit);
            List<RuntimeCapabilityHealth.Issue> issueDetails = safeHealth.issues().stream()
                    .limit(effectiveIssueDetailLimit)
                    .toList();
            return new RuntimeCapabilityReadiness(
                    ready,
                    safeHealth.status(),
                    safeHealth.issues().stream()
                            .map(RuntimeCapabilityHealth.Issue::code)
                            .toList(),
                    issueDetails,
                    safeHealth.issues().size(),
                    effectiveIssueDetailLimit,
                    safeHealth.issues().size() > issueDetails.size(),
                    cache,
                    effectivePolicy,
                    ready ? null : rejectionReason(safeHealth.status()),
                    safeHealth.observedAt());
        }

        private static String rejectionReason(RuntimeCapabilityHealth.Status status) {
            return status == RuntimeCapabilityHealth.Status.UNAVAILABLE
                    ? "runtime-capability-unavailable"
                    : "runtime-capability-health-not-accepted";
        }
    }

    public record RuntimeCapabilityReadinessPolicy(boolean acceptDegraded) {

        public boolean ready(RuntimeCapabilityHealth.Status status) {
            return switch (status != null ? status : RuntimeCapabilityHealth.Status.READY) {
                case READY -> true;
                case DEGRADED -> acceptDegraded;
                case UNAVAILABLE -> false;
            };
        }
    }

    /**
     * Cache metadata attached to runtime capability probe responses.
     */
    public record RuntimeCapabilityCache(
            boolean enabled,
            boolean hit,
            long ttlMillis,
            long ageMillis,
            Instant expiresAt) {

        public RuntimeCapabilityCache {
            ttlMillis = Math.max(0L, ttlMillis);
            ageMillis = Math.max(0L, ageMillis);
        }

        private static RuntimeCapabilityCache disabled() {
            return new RuntimeCapabilityCache(false, false, 0, 0, null);
        }

        private static RuntimeCapabilityCache miss(Duration ttl, Instant cachedAt) {
            return new RuntimeCapabilityCache(
                    true,
                    false,
                    ttl.toMillis(),
                    0,
                    cachedAt.plus(ttl));
        }

        private static RuntimeCapabilityCache hit(Duration ttl, Instant cachedAt, Instant now) {
            return new RuntimeCapabilityCache(
                    true,
                    true,
                    ttl.toMillis(),
                    Duration.between(cachedAt, now).toMillis(),
                    cachedAt.plus(ttl));
        }
    }

    private record CachedRuntimeCapabilities(
            RuntimeCapabilities capabilities,
            Instant cachedAt,
            Duration ttl) {

        private boolean expired(Instant now) {
            return !now.isBefore(cachedAt.plus(ttl));
        }
    }
}
