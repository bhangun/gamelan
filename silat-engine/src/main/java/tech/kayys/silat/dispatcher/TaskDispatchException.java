package tech.kayys.silat.dispatcher;

public class TaskDispatchException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public TaskDispatchException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public TaskDispatchException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = null;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
