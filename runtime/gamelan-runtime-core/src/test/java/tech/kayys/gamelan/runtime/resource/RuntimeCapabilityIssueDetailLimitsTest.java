package tech.kayys.gamelan.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuntimeCapabilityIssueDetailLimitsTest {

    @Test
    void normalizeDefaultsMissingLimit() {
        assertEquals(20, RuntimeCapabilityIssueDetailLimits.normalize(null));
    }

    @Test
    void normalizeClampsNegativeLimitToZero() {
        assertEquals(0, RuntimeCapabilityIssueDetailLimits.normalize(-1));
    }

    @Test
    void normalizeAllowsZeroDetailLimit() {
        assertEquals(0, RuntimeCapabilityIssueDetailLimits.normalize(0));
    }

    @Test
    void normalizeClampsOversizedLimitToMaximum() {
        assertEquals(100, RuntimeCapabilityIssueDetailLimits.normalize(1_000));
    }
}
