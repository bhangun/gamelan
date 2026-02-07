package tech.kayys.gamelan.runtime.error;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.gamelan.engine.error.ErrorResponse;

@Provider
public class GamelanExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    public Response toResponse(Throwable exception) {
        ErrorResponse response = ErrorResponse.fromException(exception);
        int status = response.getHttpStatus();
        return Response.status(status).entity(response).build();
    }
}
