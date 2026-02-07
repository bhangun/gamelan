package tech.kayys.gamelan.dispatcher;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

public class TaskDispatchException extends GamelanException {

    private final int statusCode;
    private final String responseBody;

    public TaskDispatchException(String message, int statusCode, String responseBody) {
        super(resolveErrorCode(statusCode), message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public TaskDispatchException(String message, Throwable cause) {
        super(ErrorCode.TASK_DISPATCH_FAILED, message, cause);
        this.statusCode = -1;
        this.responseBody = null;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    private static ErrorCode resolveErrorCode(int statusCode) {
        if (statusCode == 400) {
            return ErrorCode.DISPATCHER_INVALID_REQUEST;
        }
        if (statusCode == 404) {
            return ErrorCode.DISPATCHER_NOT_FOUND;
        }
        if (statusCode == 408 || statusCode == 504) {
            return ErrorCode.DISPATCHER_TIMEOUT;
        }
        if (statusCode == 503) {
            return ErrorCode.TASK_EXECUTOR_UNAVAILABLE;
        }
        if (statusCode >= 500) {
            return ErrorCode.DISPATCHER_BAD_RESPONSE;
        }
        return ErrorCode.TASK_DISPATCH_FAILED;
    }
}
