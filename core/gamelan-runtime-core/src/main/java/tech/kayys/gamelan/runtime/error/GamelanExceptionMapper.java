package tech.kayys.gamelan.runtime.error;

import java.time.Instant;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorResponse;

@Provider
public class GamelanExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException webEx) {
            int status = webEx.getResponse() != null ? webEx.getResponse().getStatus() : 500;
            ErrorCode mapped = status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
            String message = exception.getMessage() != null ? exception.getMessage() : mapped.getDefaultMessage();
            ErrorResponse response = ErrorResponse.builder()
                    .errorCode(mapped.getCode())
                    .message(message)
                    .httpStatus(status)
                    .retryable(status >= 500)
                    .timestamp(Instant.now())
                    .build();
            return Response.status(status).entity(response).build();
        }

        ErrorResponse response = ErrorResponse.fromException(exception);
        int status = response.getHttpStatus();
        return Response.status(status).entity(response).build();
    }
}
