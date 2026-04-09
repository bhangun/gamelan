package tech.kayys.gamelan.engine.context;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.RequestScoped;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Per-request context holding tracing and optional tenant identity.
 * Always available regardless of deployment mode.
 * TenantId is only populated when multi-tenancy is enabled.
 */
@RequestScoped
public class RequestContext {

    private String requestId = UUID.randomUUID().toString();
    private TenantId tenantId;
    private String userId;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Optional<TenantId> getTenantId() {
        return Optional.ofNullable(tenantId);
    }

    public void setTenantId(TenantId tenantId) {
        // tenantId field is nullable — absence means single-tenant / not yet resolved
        this.tenantId = tenantId;
    }

    public void clearTenantId() {
        this.tenantId = null;
    }

    public boolean hasTenant() {
        return tenantId != null;
    }

    public Optional<String> getUserId() {
        return Optional.ofNullable(userId);
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
