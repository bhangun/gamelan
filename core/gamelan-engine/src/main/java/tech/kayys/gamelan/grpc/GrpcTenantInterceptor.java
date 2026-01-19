package tech.kayys.gamelan.grpc;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * gRPC interceptor for tenant resolution
 */
@ApplicationScoped
public class GrpcTenantInterceptor {

    private static final io.grpc.Context.Key<String> TENANT_KEY = io.grpc.Context.key("tenant-id");

    public TenantId getCurrentTenantId() {
        String tenantId = TENANT_KEY.get();
        if (tenantId == null) {
            throw new SecurityException("Tenant ID not found in context");
        }
        return TenantId.of(tenantId);
    }

    public void setTenantId(String tenantId) {
        io.grpc.Context.current()
                .withValue(TENANT_KEY, tenantId)
                .run(() -> {
                });
    }
}
