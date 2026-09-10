package tech.kayys.gamelan.runtime.resource;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.kayys.gamelan.engine.config.GamelanConfig;
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.engine.signal.dto.ExternalSignalRequest;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

@Path("/api/v1/callbacks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CallbackResource {

    static final String CALLBACK_TOKEN_HEADER = "X-Gamelan-Callback-Token";

    @Inject
    WorkflowRunManager runManager;

    @Inject
    RequestContext requestContext;

    @Inject
    GamelanConfig config;

    private TenantId tenant() {
        return requestContext.getTenantId().orElseGet(config::getDefaultTenant);
    }

    @POST
    @Path("/{runId}/signal")
    public Uni<Void> signal(
            @PathParam("runId") String runId,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @HeaderParam(CALLBACK_TOKEN_HEADER) String callbackTokenHeader,
            @QueryParam("token") String queryToken,
            ExternalSignalRequest signal) {
        String callbackToken = resolveCallbackToken(authorizationHeader, callbackTokenHeader, queryToken);
        if (callbackToken == null) {
            return Uni.createFrom().failure(unauthorized("Missing callback token"));
        }
        WebApplicationException invalidSignal = validateSignal(signal);
        if (invalidSignal != null) {
            return Uni.createFrom().failure(invalidSignal);
        }

        return runManager.onExternalSignal(WorkflowRunId.of(runId), tenant(), signal, callbackToken)
                .onFailure(SecurityException.class)
                .transform(error -> unauthorized("Invalid callback token"));
    }

    static String resolveCallbackToken(String authorizationHeader, String callbackTokenHeader, String queryToken) {
        String bearerToken = bearerToken(authorizationHeader);
        if (bearerToken != null) {
            return bearerToken;
        }
        if (callbackTokenHeader != null && !callbackTokenHeader.isBlank()) {
            return callbackTokenHeader.trim();
        }
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken.trim();
        }
        return null;
    }

    static WebApplicationException validateSignal(ExternalSignalRequest signal) {
        if (signal == null) {
            return badRequest("Missing external signal body");
        }
        if (signal.signalType() == null || signal.signalType().isBlank()) {
            return badRequest("Missing signalType");
        }
        if (signal.targetNodeId() == null || signal.targetNodeId().isBlank()) {
            return badRequest("Missing targetNodeId");
        }
        return null;
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }

        String value = authorizationHeader.trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }

        String token = value.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    private static WebApplicationException unauthorized(String message) {
        return new WebApplicationException(
                message,
                Response.status(Response.Status.UNAUTHORIZED)
                        .entity(new ErrorResponse(message))
                        .build());
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(
                message,
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse(message))
                        .build());
    }

    public record ErrorResponse(String error) {
    }
}
