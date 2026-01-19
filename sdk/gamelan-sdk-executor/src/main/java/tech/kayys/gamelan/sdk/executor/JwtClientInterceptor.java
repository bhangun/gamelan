package tech.kayys.gamelan.sdk.executor;

import io.grpc.*;

/**
 * gRPC interceptor that adds a JWT token to the request metadata
 */
public class JwtClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION_KEY = Metadata.Key.of("Authorization",
            Metadata.ASCII_STRING_MARSHALLER);

    private final String token;

    public JwtClientInterceptor(String token) {
        this.token = token;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (token != null && !token.isEmpty()) {
                    headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
