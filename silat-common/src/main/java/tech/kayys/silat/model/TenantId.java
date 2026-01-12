package tech.kayys.silat.model;

import java.util.Objects;

/**
 * Tenant Identifier for multi-tenancy support
 */
public record TenantId(String value) {
    public TenantId {
        Objects.requireNonNull(value, "TenantId cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TenantId cannot be blank");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    public static TenantId system() {
        return new TenantId("system");
    }
}
