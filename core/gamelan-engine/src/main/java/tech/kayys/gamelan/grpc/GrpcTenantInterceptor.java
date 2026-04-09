package tech.kayys.gamelan.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.config.GamelanConfig;
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * gRPC server interceptor that extracts tenant-id from metadata
 * and populates the @RequestScoped RequestContext.
 */
@GlobalInterceptor
@ApplicationScoped
public class GrpcTenantInterceptor implements ServerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcTenantInterceptor.class);

    static final Metadata.Key<String> TENANT_METADATA_KEY =
            Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> REQUEST_ID_METADATA_KEY =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    @Inject
    RequestContext requestContext;

    @Inject
    GamelanConfig config;

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {

        String requestId = headers.get(REQUEST_ID_METADATA_KEY);
        if (requestId != null && !requestId.isBlank()) {
            requestContext.setRequestId(requestId);
        }

        String tenantIdStr = headers.get(TENANT_METADATA_KEY);
        if (tenantIdStr != null && !tenantIdStr.isBlank()) {
            requestContext.setTenantId(new TenantId(tenantIdStr));
            LOG.debug("gRPC tenant set from metadata: {}", tenantIdStr);
        } else if (!config.isMultiTenancyEnabled()) {
            requestContext.setTenantId(config.getDefaultTenant());
        }

        return Contexts.interceptCall(Context.current(), call, headers, next);
    }
}
