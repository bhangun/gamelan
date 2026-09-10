package tech.kayys.gamelan.runtime.lifecycle;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.StartupEvent;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeCapabilityReadiness;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeCapabilityReadinessPolicy;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityHealth;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityIssueDetailLimits;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityInspector;

/**
 * Validates discovered runtime capabilities during application startup.
 */
@ApplicationScoped
public class RuntimeCapabilityStartupValidator {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeCapabilityStartupValidator.class);

    @Inject
    RuntimeCapabilityInspector capabilityInspector;

    @ConfigProperty(name = "gamelan.runtime.capabilities.startup-validation.mode", defaultValue = "warn")
    String startupValidationMode;

    @ConfigProperty(name = "gamelan.runtime.capabilities.startup-validation.accept-degraded", defaultValue = "true")
    Boolean startupValidationAcceptDegraded;

    @ConfigProperty(name = "gamelan.runtime.capabilities.startup-validation.issue-detail-limit", defaultValue = "20")
    Integer startupValidationIssueDetailLimit;

    void onStart(@Observes StartupEvent event) {
        validateStartupCapabilities();
    }

    public RuntimeCapabilityStartupValidationResult validateStartupCapabilities() {
        RuntimeCapabilityStartupValidationMode mode = RuntimeCapabilityStartupValidationMode.from(startupValidationMode);
        if (mode == RuntimeCapabilityStartupValidationMode.DISABLED) {
            RuntimeCapabilityStartupValidationResult result = RuntimeCapabilityStartupValidationResult.skipped(mode);
            LOG.info("Runtime capability startup validation is disabled.");
            return result;
        }

        RuntimeCapabilityHealth health = capabilityInspector != null
                ? capabilityInspector.health()
                : RuntimeCapabilityHealth.fromIssues(List.of(
                        new RuntimeCapabilityHealth.Issue(
                                "runtime-capability-inspector-missing",
                                RuntimeCapabilityHealth.Severity.ERROR,
                                "runtimeCapabilityInspector",
                                null,
                                "Runtime capability inspector is not available.")),
                        Instant.now());
        RuntimeCapabilityReadiness readiness = RuntimeCapabilityReadiness.from(
                health,
                new RuntimeCapabilityReadinessPolicy(defaultTrue(startupValidationAcceptDegraded)),
                startupValidationIssueDetailLimit);
        RuntimeCapabilityStartupValidationResult result =
                RuntimeCapabilityStartupValidationResult.from(mode, readiness);

        if (!readiness.ready()) {
            String message = "Runtime capability startup validation failed: " + result.summary();
            if (mode == RuntimeCapabilityStartupValidationMode.FAIL) {
                throw new IllegalStateException(message);
            }
            LOG.warn(message);
            return result;
        }
        if (health.status() != RuntimeCapabilityHealth.Status.READY) {
            LOG.warn("Runtime capability startup validation completed with degraded health: {}", result.summary());
            return result;
        }
        LOG.info("Runtime capability startup validation passed: {}", result.summary());
        return result;
    }

    private static boolean defaultTrue(Boolean value) {
        return value == null || Boolean.TRUE.equals(value);
    }

    /**
     * Startup validation outcome with enough bounded issue detail for boot diagnostics.
     */
    public record RuntimeCapabilityStartupValidationResult(
            RuntimeCapabilityStartupValidationMode mode,
            boolean ready,
            RuntimeCapabilityHealth.Status status,
            List<String> issueCodes,
            List<RuntimeCapabilityHealth.Issue> issues,
            int totalIssueCount,
            int issueDetailLimit,
            boolean issueDetailsTruncated,
            String rejectionReason,
            Instant observedAt) {

        public RuntimeCapabilityStartupValidationResult {
            status = status != null ? status : RuntimeCapabilityHealth.Status.READY;
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
            issues = issues == null ? List.of() : List.copyOf(issues);
            issueDetailLimit = RuntimeCapabilityIssueDetailLimits.normalize(issueDetailLimit);
            totalIssueCount = Math.max(totalIssueCount, issues.size());
            issueDetailsTruncated = issueDetailsTruncated || totalIssueCount > issues.size();
            observedAt = observedAt != null ? observedAt : Instant.now();
        }

        static RuntimeCapabilityStartupValidationResult skipped(RuntimeCapabilityStartupValidationMode mode) {
            return new RuntimeCapabilityStartupValidationResult(
                    mode,
                    true,
                    RuntimeCapabilityHealth.Status.READY,
                    List.of(),
                    List.of(),
                    0,
                    RuntimeCapabilityIssueDetailLimits.normalize(null),
                    false,
                    null,
                    Instant.now());
        }

        static RuntimeCapabilityStartupValidationResult from(
                RuntimeCapabilityStartupValidationMode mode,
                RuntimeCapabilityReadiness readiness) {
            return new RuntimeCapabilityStartupValidationResult(
                    mode,
                    readiness.ready(),
                    readiness.status(),
                    readiness.issueCodes(),
                    readiness.issues(),
                    readiness.totalIssueCount(),
                    readiness.issueDetailLimit(),
                    readiness.issueDetailsTruncated(),
                    readiness.rejectionReason(),
                    readiness.observedAt());
        }

        String summary() {
            return "mode=" + mode
                    + ", ready=" + ready
                    + ", status=" + status
                    + ", issueCodes=" + issueCodes
                    + ", totalIssueCount=" + totalIssueCount
                    + ", issueDetailLimit=" + issueDetailLimit
                    + (issueDetailsTruncated ? ", issueDetailsTruncated=true" : "")
                    + issueDetailsSummary()
                    + (rejectionReason != null ? ", rejectionReason=" + rejectionReason : "");
        }

        private String issueDetailsSummary() {
            if (issues.isEmpty()) {
                return "";
            }
            return ", issues=" + issues.stream()
                    .map(RuntimeCapabilityStartupValidationResult::summarizeIssue)
                    .toList();
        }

        private static String summarizeIssue(RuntimeCapabilityHealth.Issue issue) {
            String component = issue.component() != null ? "(" + issue.component() + ")" : "";
            return issue.code() + component + ": " + issue.message();
        }
    }
}
