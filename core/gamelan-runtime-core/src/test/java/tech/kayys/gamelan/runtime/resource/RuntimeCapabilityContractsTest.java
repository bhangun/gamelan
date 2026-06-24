package tech.kayys.gamelan.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeProfile;

class RuntimeCapabilityContractsTest {

    @Test
    void resolveWhenConfiguredAutoInfersDistributedContractFromProfile() {
        RuntimeCapabilityContract contract = RuntimeCapabilityContracts.resolve(
                "auto",
                profile("production-distributed"));

        assertEquals("distributed", contract.name());
        assertEquals("auto", contract.selectedBy());
        assertTrue(contract.enabled());
        assertTrue(contract.allowedWorkflowPersistenceStores().contains("postgres"));
        assertTrue(contract.disallowedRegistryPersistenceTypes().contains("memory"));
        assertTrue(contract.allowedEventPublisherFamilies().contains("kafka"));
        assertTrue(contract.allowedWakeupOutboxStores().contains("postgres"));
        assertTrue(contract.eventPublisherRequired());
        assertTrue(contract.wakeupPublisherRequired());
    }

    @Test
    void resolveWhenConfiguredOfflineAliasSelectsOfflineAgentContract() {
        RuntimeCapabilityContract contract = RuntimeCapabilityContracts.resolve(
                "offline",
                profile("dev"));

        assertEquals("offline-agent", contract.name());
        assertEquals("configured", contract.selectedBy());
        assertEquals(1, contract.allowedWorkflowPersistenceStores().size());
        assertTrue(contract.allowedWorkflowPersistenceStores().contains("file"));
        assertTrue(contract.allowedAgentContextStores().contains("file"));
        assertTrue(contract.allowedSchedulerModes().contains("local"));
        assertTrue(contract.allowedEventPublisherFamilies().contains("local"));
        assertTrue(contract.allowedWakeupOutboxStores().contains("file"));
        assertTrue(contract.wakeupPublisherRequired());
        assertTrue(contract.recoveryLeaseRequired());
    }

    @Test
    void resolveWhenConfiguredDisabledDisablesContractChecks() {
        RuntimeCapabilityContract contract = RuntimeCapabilityContracts.resolve(
                "disabled",
                profile("production"));

        assertEquals("none", contract.name());
        assertFalse(contract.enabled());
    }

    @Test
    void resolveWhenConfiguredProductionRequiresSharedDurability() {
        RuntimeCapabilityContract contract = RuntimeCapabilityContracts.resolve(
                "production",
                profile("prod"));

        assertEquals("production", contract.name());
        assertTrue(contract.allowedWorkflowPersistenceStores().contains("postgres"));
        assertFalse(contract.allowedWorkflowPersistenceStores().contains("file"));
        assertTrue(contract.allowedAgentContextStores().contains("postgres"));
        assertFalse(contract.allowedAgentContextStores().contains("file"));
        assertTrue(contract.disallowedRegistryPersistenceTypes().contains("memory"));
        assertTrue(contract.allowedSchedulerModes().contains("redis"));
        assertTrue(contract.allowedEventPublisherFamilies().contains("kafka"));
        assertTrue(contract.allowedWakeupOutboxStores().contains("postgres"));
        assertTrue(contract.recoveryLeaseRequired());
    }

    private static RuntimeProfile profile(String quarkusProfile) {
        return new RuntimeProfile(
                quarkusProfile,
                "memory",
                "default",
                "memory",
                "round-robin",
                false,
                false,
                "memory",
                "local",
                "local",
                "auto",
                false);
    }
}
