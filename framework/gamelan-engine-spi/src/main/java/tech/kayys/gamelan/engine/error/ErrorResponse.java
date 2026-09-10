package tech.kayys.gamelan.engine.error;

import java.time.Instant;

public class ErrorResponse {
    private String errorCode;
    private String message;
    private Instant timestamp;
    private int httpStatus;
    private boolean retryable;

    public ErrorResponse() {
    }

    public ErrorResponse(String errorCode, String message, Instant timestamp) {
        this.errorCode = errorCode;
        this.message = message;
        this.timestamp = timestamp;
        this.httpStatus = 500;
        this.retryable = false;
    }

    public ErrorResponse(String errorCode, String message, Instant timestamp, int httpStatus, boolean retryable) {
        this.errorCode = errorCode;
        this.message = message;
        this.timestamp = timestamp;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    public static ErrorResponse fromException(Throwable throwable) {
        if (throwable instanceof GamelanException ge) {
            return ErrorResponse.builder()
                    .errorCode(ge.getErrorCode().getCode())
                    .message(ge.getSafeMessage())
                    .httpStatus(ge.getHttpStatusCode())
                    .retryable(ge.isRetryable())
                    .timestamp(Instant.now())
                    .build();
        }
        return ErrorResponse.builder()
                .errorCode(ErrorCode.INTERNAL_ERROR.getCode())
                .message("Internal server error")
                .httpStatus(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .retryable(ErrorCode.INTERNAL_ERROR.isRetryable())
                .timestamp(Instant.now())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String errorCode;
        private String message;
        private Instant timestamp;
        private int httpStatus = 500;
        private boolean retryable;

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder httpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        public ErrorResponse build() {
            return new ErrorResponse(errorCode, message, timestamp, httpStatus, retryable);
        }
    }
}
