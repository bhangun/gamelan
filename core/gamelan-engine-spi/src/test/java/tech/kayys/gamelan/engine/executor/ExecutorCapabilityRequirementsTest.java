package tech.kayys.gamelan.engine.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.protocol.CommunicationType;

class ExecutorCapabilityRequirementsTest {

    @Test
    void fromContextParsesRequiredPreferredAndExcludedCapabilities() {
        ExecutorCapabilityRequirements requirements = ExecutorCapabilityRequirements.fromContext(Map.of(
                ExecutorCapabilityRequirements.REQUIRED_CAPABILITIES_KEY, List.of(" Coding ", "SANDBOX"),
                ExecutorCapabilityRequirements.PREFERRED_CAPABILITIES_KEY, " browser gpu ",
                ExecutorCapabilityRequirements.EXCLUDED_CAPABILITIES_KEY, "finance;pii"));

        assertEquals(Set.of("coding", "sandbox"), requirements.requiredCapabilities());
        assertEquals(Set.of("browser", "gpu"), requirements.preferredCapabilities());
        assertEquals(Set.of("finance", "pii"), requirements.excludedCapabilities());
        assertTrue(requirements.hasHardConstraints());
    }

    @Test
    void capabilitiesKeyRemainsRequiredCapabilityAlias() {
        ExecutorCapabilityRequirements requirements = ExecutorCapabilityRequirements.fromContext(Map.of(
                ExecutorCapabilityRequirements.CAPABILITIES_KEY, " coding sandbox "));

        assertEquals(Set.of("coding", "sandbox"), requirements.requiredCapabilities());
    }

    @Test
    void hardMatchRejectsMissingRequiredAndMatchedExcludedCapabilities() {
        ExecutorCapabilityRequirements requirements = new ExecutorCapabilityRequirements(
                Set.of("coding", "sandbox"),
                Set.of("browser"),
                Set.of("finance"));

        ExecutorCapabilityRequirements.CapabilityMatch missingRequired = requirements.evaluate(executor(
                "agent-1",
                "coding browser"));
        ExecutorCapabilityRequirements.CapabilityMatch excluded = requirements.evaluate(executor(
                "agent-2",
                "coding sandbox finance"));

        assertFalse(missingRequired.matched());
        assertEquals(Set.of("sandbox"), missingRequired.missingRequiredCapabilities());
        assertEquals(
                ExecutorSelectionRejectionReasons.REQUIRED_CAPABILITY_MISMATCH,
                missingRequired.rejectionReason());
        assertFalse(excluded.matched());
        assertEquals(Set.of("finance"), excluded.matchedExcludedCapabilities());
        assertEquals(
                ExecutorSelectionRejectionReasons.EXCLUDED_CAPABILITY_PRESENT,
                excluded.rejectionReason());
    }

    @Test
    void preferredCapabilitiesAreSoftBiasOnly() {
        ExecutorCapabilityRequirements requirements = new ExecutorCapabilityRequirements(
                Set.of("coding"),
                Set.of("browser"),
                Set.of());

        ExecutorInfo codingOnly = executor("agent-1", "coding");
        ExecutorInfo browserCapable = executor("agent-2", "coding browser");

        assertTrue(requirements.hardMatches(codingOnly));
        assertFalse(requirements.preferredBy(codingOnly));
        assertTrue(requirements.preferredBy(browserCapable));
    }

    private static ExecutorInfo executor(String executorId, String capabilities) {
        return new ExecutorInfo(
                executorId,
                "agent",
                CommunicationType.GRPC,
                "endpoint",
                Duration.ofSeconds(30),
                Map.of(ExecutorSelectionPolicy.METADATA_CAPABILITIES_KEY, capabilities));
    }
}
