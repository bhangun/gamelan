package tech.kayys.gamelan.runtime.resource;

import java.util.List;

/**
 * Declares the capability expectations selected for a runtime profile.
 */
public record RuntimeCapabilityContract(
        String name,
        String selectedBy,
        boolean enabled,
        List<String> allowedWorkflowPersistenceStores,
        List<String> allowedAgentContextStores,
        List<String> disallowedRegistryPersistenceTypes,
        List<String> allowedGrpcTaskStreamBrokers,
        List<String> allowedSchedulerModes,
        List<String> allowedEventPublisherFamilies,
        List<String> allowedWakeupOutboxStores,
        boolean eventPublisherRequired,
        boolean wakeupPublisherRequired,
        boolean recoveryLeaseRequired) {

    public RuntimeCapabilityContract {
        name = valueOrDefault(name, "local");
        selectedBy = valueOrDefault(selectedBy, "configured");
        allowedWorkflowPersistenceStores = copyOf(allowedWorkflowPersistenceStores);
        allowedAgentContextStores = copyOf(allowedAgentContextStores);
        disallowedRegistryPersistenceTypes = copyOf(disallowedRegistryPersistenceTypes);
        allowedGrpcTaskStreamBrokers = copyOf(allowedGrpcTaskStreamBrokers);
        allowedSchedulerModes = copyOf(allowedSchedulerModes);
        allowedEventPublisherFamilies = copyOf(allowedEventPublisherFamilies);
        allowedWakeupOutboxStores = copyOf(allowedWakeupOutboxStores);
    }

    private static List<String> copyOf(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
