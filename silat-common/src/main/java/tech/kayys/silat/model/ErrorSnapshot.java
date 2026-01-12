package tech.kayys.silat.model;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ErrorSnapshot {
    private final String code;
    private final String message;
    private final String stackTrace;

    @JsonCreator
    public ErrorSnapshot(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("stackTrace") String stackTrace) {
        this.code = code;
        this.message = message;
        this.stackTrace = stackTrace;
    }

    public String code() { return code; }
    public String message() { return message; }
    public String stackTrace() { return stackTrace; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ErrorSnapshot that = (ErrorSnapshot) o;
        return Objects.equals(code, that.code) && Objects.equals(message, that.message) && Objects.equals(stackTrace, that.stackTrace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, stackTrace);
    }

    @Override
    public String toString() {
        return "ErrorSnapshot{" +
                "code='" + code + '\'' +
                ", message='" + message + '\'' +
                ", stackTrace='" + stackTrace + '\'' +
                '}';
    }
}
