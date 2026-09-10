package tech.kayys.gamelan.engine.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.config.GamelanConfig;
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.grpc.GrpcWorkflowDefinitionMapper;
import tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.DefinitionResponse;
import tech.kayys.gamelan.grpc.v1.DeleteDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.GetDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.ListDefinitionsRequest;
import tech.kayys.gamelan.grpc.v1.ListDefinitionsResponse;
import tech.kayys.gamelan.grpc.v1.UpdateDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.ValidateDefinitionRequest;
import tech.kayys.gamelan.grpc.v1.ValidationResponse;

/**
 * gRPC workflow-definition service for remote SDK and distributed runtimes.
 */
@GrpcService
public class WorkflowDefinitionServiceImpl implements tech.kayys.gamelan.grpc.v1.WorkflowDefinitionService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowDefinitionServiceImpl.class);

    @Inject
    tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService service;

    @Inject
    RequestContext requestContext;

    @Inject
    GamelanConfig config;

    @Override
    public Uni<DefinitionResponse> createDefinition(CreateDefinitionRequest request) {
        TenantId tenantId = tenant(request.getTenantId());
        LOG.info("gRPC: Creating workflow definition: {}", request.getName());

        return service.create(GrpcWorkflowDefinitionMapper.toCreateRequest(request), tenantId)
                .map(definition -> GrpcWorkflowDefinitionMapper.toProtoDefinitionResponse(definition, true))
                .onFailure().transform(this::mapException);
    }

    @Override
    public Uni<DefinitionResponse> getDefinition(GetDefinitionRequest request) {
        TenantId tenantId = tenant(request.getTenantId());
        Uni<WorkflowDefinition> load = !request.getName().isBlank()
                ? service.getByName(request.getName(), tenantId)
                : service.get(WorkflowDefinitionId.of(request.getDefinitionId()), tenantId);

        return load.map(definition -> GrpcWorkflowDefinitionMapper.toProtoDefinitionResponse(definition, true))
                .onFailure().transform(this::mapException);
    }

    @Override
    public Uni<ListDefinitionsResponse> listDefinitions(ListDefinitionsRequest request) {
        TenantId tenantId = tenant(request.getTenantId());
        return service.list(tenantId, request.getActiveOnly())
                .map(definitions -> {
                    ListDefinitionsResponse.Builder builder = ListDefinitionsResponse.newBuilder()
                            .setTotal(definitions.size());
                    definitions.forEach(definition -> builder.addDefinitions(
                            GrpcWorkflowDefinitionMapper.toProtoDefinitionResponse(definition, true)));
                    return builder.build();
                })
                .onFailure().transform(this::mapException);
    }

    @Override
    public Uni<DefinitionResponse> updateDefinition(UpdateDefinitionRequest request) {
        TenantId tenantId = tenant(request.getTenantId());
        return service.update(
                WorkflowDefinitionId.of(request.getDefinitionId()),
                GrpcWorkflowDefinitionMapper.toUpdateRequest(request),
                tenantId)
                .map(definition -> GrpcWorkflowDefinitionMapper.toProtoDefinitionResponse(
                        definition,
                        request.hasIsActive() ? request.getIsActive() : true))
                .onFailure().transform(this::mapException);
    }

    @Override
    public Uni<Empty> deleteDefinition(DeleteDefinitionRequest request) {
        TenantId tenantId = tenant(request.getTenantId());
        return service.delete(WorkflowDefinitionId.of(request.getDefinitionId()), tenantId)
                .map(ignored -> Empty.getDefaultInstance())
                .onFailure().transform(this::mapException);
    }

    @Override
    public Uni<ValidationResponse> validateDefinition(ValidateDefinitionRequest request) {
        try {
            TenantId tenantId = tenant(request.getDefinition().getTenantId());
            ValidationResult validation = GrpcWorkflowDefinitionMapper
                    .toDomainDefinition(request.getDefinition(), tenantId)
                    .validate();

            ValidationResponse.Builder response = ValidationResponse.newBuilder()
                    .setIsValid(validation.isValid())
                    .addAllErrors(validation.errors());
            if (!validation.isValid()) {
                response.addWarnings(validation.message());
            }
            return Uni.createFrom().item(response.build());
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            if (message == null || message.isBlank()) {
                message = "Invalid workflow definition";
            }
            return Uni.createFrom().item(ValidationResponse.newBuilder()
                    .setIsValid(false)
                    .addErrors(message)
                    .build());
        }
    }

    private TenantId tenant(String requestTenantId) {
        return requestContext.getTenantId()
                .orElseGet(() -> requestTenantId != null && !requestTenantId.isBlank()
                        ? TenantId.of(requestTenantId)
                        : config.getDefaultTenant());
    }

    private Throwable mapException(Throwable throwable) {
        LOG.error("gRPC workflow-definition error", throwable);

        ErrorCode errorCode = mapErrorCode(throwable);
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = errorCode.getDefaultMessage();
        }

        return mapStatus(errorCode.getHttpStatus())
                .withDescription(errorCode.getCode() + ": " + message)
                .asRuntimeException();
    }

    private ErrorCode mapErrorCode(Throwable throwable) {
        if (throwable instanceof GamelanException ge) {
            return ge.getErrorCode();
        }
        if (throwable instanceof java.util.NoSuchElementException) {
            return ErrorCode.WORKFLOW_NOT_FOUND;
        }
        if (throwable instanceof IllegalArgumentException) {
            return ErrorCode.VALIDATION_FAILED;
        }
        if (throwable instanceof SecurityException) {
            return ErrorCode.TENANT_UNAUTHORIZED;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private Status mapStatus(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> Status.INVALID_ARGUMENT;
            case 401 -> Status.UNAUTHENTICATED;
            case 403 -> Status.PERMISSION_DENIED;
            case 404 -> Status.NOT_FOUND;
            case 409 -> Status.FAILED_PRECONDITION;
            case 429 -> Status.RESOURCE_EXHAUSTED;
            case 502, 503 -> Status.UNAVAILABLE;
            case 504 -> Status.DEADLINE_EXCEEDED;
            default -> Status.INTERNAL;
        };
    }
}
