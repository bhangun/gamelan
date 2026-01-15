package tech.kayys.silat.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Tenant Identifier for multi-tenancy support
 */
public record TenantId(String value) {
    public TenantId {
        Objects.requireNonNull(value, "TenantId value cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TenantId cannot be blank");
        }
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String value() {
        return value;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static TenantId of(String value) {
        return new TenantId(value);
    }

    public static TenantId system() {
        return new TenantId("system");
    }

    @Override
    public String toString() {
        return value;
    }
}
