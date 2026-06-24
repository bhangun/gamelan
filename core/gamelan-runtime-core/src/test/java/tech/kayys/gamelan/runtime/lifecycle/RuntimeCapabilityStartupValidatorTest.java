package tech.kayys.gamelan.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.runtime.lifecycle.RuntimeCapabilityStartupValidator.RuntimeCapabilityStartupValidationResult;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityHealth;
import tech.kayys.gamelan.runtime.resource.RuntimeCapabilityInspector;

class RuntimeCapabilityStartupValidatorTest {

    @Test
    void validateStartupCapabilitiesWhenDisabledSkipsInspection() throws ReflectiveOperationException {
        RuntimeCapabilityStartupValidator validator = new RuntimeCapabilityStartupValidator();
        set(validator, "startupValidationMode", "disabled");

        RuntimeCapabilityStartupValidationResult result =
                assertDoesNotThrow(validator::validateStartupCapabilities);

        assertTrue(result.ready());
        assertEquals(RuntimeCapabilityStartupValidationMode.DISABLED, result.mode());
        assertTrue(result.issueCodes().isEmpty());
        assertTrue(result.issues().isEmpty());
        assertEquals(0, result.totalIssueCount());
        assertEquals(20, result.issueDetailLimit());
        assertFalse(result.issueDetailsTruncated());
    }

    @Test
    void validateStartupCapabilitiesWhenWarnModeSeesDegradedHealthDoesNotThrow()
            throws ReflectiveOperationException {
        RuntimeCapabilityStartupValidator validator = validator(degradedHealth());
        set(validator, "startupValidationMode", "warn");
        set(validator, "startupValidationAcceptDegraded", true);

        RuntimeCapabilityStartupValidationResult result =
                assertDoesNotThrow(validator::validateStartupCapabilities);

        assertTrue(result.ready());
        assertEquals(RuntimeCapabilityStartupValidationMode.WARN, result.mode());
        assertEquals(RuntimeCapabilityHealth.Status.DEGRADED, result.status());
        assertTrue(result.issueCodes().contains("event-publisher-failures"));
        assertEquals(1, result.totalIssueCount());
        assertEquals(20, result.issueDetailLimit());
        assertFalse(result.issueDetailsTruncated());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> "event-publisher-failures".equals(issue.code())
                        && "eventPublisher".equals(issue.component())
                        && "event publisher is degraded".equals(issue.message())));
        assertTrue(result.summary().contains("event-publisher-failures(eventPublisher): event publisher is degraded"));
    }

    @Test
    void validateStartupCapabilitiesWhenFailModeRejectsUnavailableHealth()
            throws ReflectiveOperationException {
        RuntimeCapabilityStartupValidator validator = validator(unavailableHealth());
        set(validator, "startupValidationMode", "fail");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, validator::validateStartupCapabilities);

        assertTrue(error.getMessage().contains("runtime-capability-unavailable"));
        assertTrue(error.getMessage().contains("component-unavailable"));
        assertTrue(error.getMessage().contains("workflowRunRepository"));
        assertTrue(error.getMessage().contains("required component is missing"));
    }

    @Test
    void validateStartupCapabilitiesWhenFailModeRejectsDegradedHealthIfConfiguredStrictly()
            throws ReflectiveOperationException {
        RuntimeCapabilityStartupValidator validator = validator(degradedHealth());
        set(validator, "startupValidationMode", "fail");
        set(validator, "startupValidationAcceptDegraded", false);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, validator::validateStartupCapabilities);

        assertTrue(error.getMessage().contains("runtime-capability-health-not-accepted"));
        assertTrue(error.getMessage().contains("event-publisher-failures"));
        assertTrue(error.getMessage().contains("eventPublisher"));
        assertTrue(error.getMessage().contains("event publisher is degraded"));
    }

    @Test
    void validateStartupCapabilitiesWhenFailModeAcceptsDegradedHealthDoesNotThrow()
            throws ReflectiveOperationException {
        RuntimeCapabilityStartupValidator validator = validator(degradedHealth());
        set(validator, "startupValidationMode", "fail");
        set(validator, "startupValidationAcceptDegraded", true);

        RuntimeCapabilityStartupValidationResult result =
                assertDoesNotThrow(validator::validateStartupCapabilities);

        assertTrue(result.ready());
        assertEquals(RuntimeCapabilityHealth.Status.DEGRADED, result.status());
        assertFalse(result.issueCodes().isEmpty());
        assertEquals(1, result.totalIssueCount());
        assertEquals(20, result.issueDetailLimit());
        assertFalse(result.issues().isEmpty());
        assertFalse(result.issueDetailsTruncated());
    }

    @Test
    void validateStartupCapabilitiesUsesConfiguredIssueDetailLimit()
            throws ReflectiveOperationException {
        RuntimeCapabilityStartupValidator validator = validator(noisyDegradedHealth());
        set(validator, "startupValidationMode", "warn");
        set(validator, "startupValidationAcceptDegraded", true);
        set(validator, "startupValidationIssueDetailLimit", 2);

        RuntimeCapabilityStartupValidationResult result =
                assertDoesNotThrow(validator::validateStartupCapabilities);

        assertTrue(result.ready());
        assertEquals(6, result.issueCodes().size());
        assertTrue(result.issueCodes().contains("issue-5"));
        assertEquals(2, result.issueDetailLimit());
        assertEquals(2, result.issues().size());
        assertEquals("issue-0", result.issues().get(0).code());
        assertEquals(6, result.totalIssueCount());
        assertTrue(result.issueDetailsTruncated());
        assertTrue(result.summary().contains("issueDetailLimit=2"));
        assertTrue(result.summary().contains("issueDetailsTruncated=true"));
    }

    private static RuntimeCapabilityStartupValidator validator(RuntimeCapabilityHealth health)
            throws ReflectiveOperationException {
        RuntimeCapabilityStartupValidator validator = new RuntimeCapabilityStartupValidator();
        set(validator, "capabilityInspector", new FixedRuntimeCapabilityInspector(health));
        return validator;
    }

    private static RuntimeCapabilityHealth degradedHealth() {
        return RuntimeCapabilityHealth.fromIssues(List.of(
                new RuntimeCapabilityHealth.Issue(
                        "event-publisher-failures",
                        RuntimeCapabilityHealth.Severity.WARN,
                        "eventPublisher",
                        "test",
                        "event publisher is degraded")),
                Instant.EPOCH);
    }

    private static RuntimeCapabilityHealth unavailableHealth() {
        return RuntimeCapabilityHealth.fromIssues(List.of(
                new RuntimeCapabilityHealth.Issue(
                        "component-unavailable",
                        RuntimeCapabilityHealth.Severity.ERROR,
                        "workflowRunRepository",
                        null,
                        "required component is missing")),
                Instant.EPOCH);
    }

    private static RuntimeCapabilityHealth noisyDegradedHealth() {
        return RuntimeCapabilityHealth.fromIssues(IntStream.range(0, 6)
                .mapToObj(index -> new RuntimeCapabilityHealth.Issue(
                        "issue-" + index,
                        RuntimeCapabilityHealth.Severity.WARN,
                        "component-" + index,
                        null,
                        "diagnostic " + index))
                .toList(),
                Instant.EPOCH);
    }

    private static void set(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FixedRuntimeCapabilityInspector extends RuntimeCapabilityInspector {

        private final RuntimeCapabilityHealth health;

        private FixedRuntimeCapabilityInspector(RuntimeCapabilityHealth health) {
            this.health = health;
        }

        @Override
        public RuntimeCapabilityHealth health() {
            return health;
        }
    }
}
