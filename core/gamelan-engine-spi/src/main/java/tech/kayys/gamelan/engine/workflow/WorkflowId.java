package tech.kayys.gamelan.engine.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Value object representing a workflow identifier.
 */
public final class WorkflowId {

    private final String id;

    private WorkflowId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "WorkflowId cannot be null or empty");
        }
        this.id = id;
    }

    @JsonCreator
    public static WorkflowId of(String id) {
        return new WorkflowId(id);
    }

    @JsonValue
    public String getId() {
        return id;
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        WorkflowId that = (WorkflowId) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
