package tech.kayys.silat.security;

import java.util.Objects;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.model.TenantId;

/**
 * Manages tenant security and isolation
 */
@ApplicationScoped
public class TenantSecurityContext {

    private static final Logger LOG = LoggerFactory.getLogger(TenantSecurityContext.class);

    private static final ThreadLocal<TenantId> CURRENT_TENANT = new ThreadLocal<>();

    @jakarta.inject.Inject
    org.eclipse.microprofile.jwt.JsonWebToken jwt;

    @ConfigProperty(name = "silat.tenant.allow-default", defaultValue = "false")
    boolean allowDefault;

    @ConfigProperty(name = "silat.tenant.default-id")
    Optional<String> defaultTenant;

    /**
     * Set current tenant for the thread
     */
    public void setCurrentTenant(TenantId tenantId) {
        CURRENT_TENANT.set(tenantId);
        LOG.trace("Set current tenant: {}", tenantId.value());
    }

    /**
     * Check if tenant context is already set without triggering default logic
     */
    public boolean isTenantSet() {
        return CURRENT_TENANT.get() != null;
    }

    /**
     * Get current tenant
     */
    public TenantId getCurrentTenant() {
        TenantId tenantId = CURRENT_TENANT.get();

        if (tenantId != null) {
            return tenantId;
        }

        // Check JWT for tenant ID
        if (jwt != null && jwt.getName() != null) {
            Optional<String> tid = jwt.claim("tenant_id");
            if (tid.isPresent()) {
                tenantId = new TenantId(tid.get());
                setCurrentTenant(tenantId);
                return tenantId;
            }
        }

        // Only use default tenant if explicitly allowed (dev only)
        if (allowDefault && defaultTenant.isPresent()) {
            LOG.warn("⚠ Using DEFAULT TENANT [{}] (dev only)", defaultTenant.get());
            TenantId defaultTenantId = new TenantId(defaultTenant.get());
            setCurrentTenant(defaultTenantId);
            return defaultTenantId;
        }

        throw new SecurityException("No tenant context set");
    }

    /**
     * Get current user
     */
    public String getCurrentUser() {
        if (jwt != null && jwt.getName() != null) {
            return jwt.getName();
        }
        return "system";
    }

    /**
     * Clear tenant context
     */
    public void clearTenantContext() {
        CURRENT_TENANT.remove();
    }

    /**
     * Validate tenant access
     */
    public Uni<Void> validateAccess(TenantId tenantId) {
        return Uni.createFrom().item(() -> {
            Objects.requireNonNull(tenantId, "Tenant ID cannot be null");

            // Validate against JWT if present
            if (jwt != null && jwt.getName() != null) {
                Optional<String> jwtTenant = jwt.claim("tenant_id");
                if (jwtTenant.isPresent() && !jwtTenant.get().equals(tenantId.value())) {
                    throw new SecurityException("Unauthorized tenant access");
                }
            }

            LOG.trace("Validated access for tenant: {}", tenantId.value());
            return null;
        });
    }

    /**
     * Check if user has permission in tenant
     */
    public Uni<Boolean> hasPermission(
            TenantId tenantId,
            String permission) {

        return Uni.createFrom().item(() -> {
            // In real implementation, check permissions from:
            // - Database
            // - LDAP
            // - External IAM service

            LOG.trace("Checking permission {} for tenant: {}",
                    permission, tenantId.value());

            return true; // Simplified
        });
    }
}
