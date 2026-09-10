Integration Overview

  1. Architecture
   - gamelan-engine is the main workflow engine that contains the business logic
   - gamelan-grpc is a separate module that provides gRPC service implementations
   - gamelan-engine depends on gamelan-grpc as a library dependency (as seen in the pom.xml)

  2. Service Integration
   - The gRPC services in gamelan-grpc (like WorkflowServiceImpl) inject and use services from gamelan-engine (like
     WorkflowRunManager)
   - The gRPC services act as an API layer that delegates to the engine's business logic
   - Tenant context is handled via the GrpcTenantInterceptor

  3. OpenTelemetry Configuration
   - The OpenTelemetry dependency is now properly configured in gamelan-engine
   - When both quarkus-grpc and quarkus-opentelemetry are present, Quarkus automatically instruments gRPC calls
   - Both client-side (in GrpcClientFactory) and server-side (in WorkflowServiceImpl, etc.) gRPC calls will be
     traced

  4. Current Setup
   - Client-side: GrpcClientFactory in gamelan-engine creates gRPC channels that will be automatically
     instrumented
   - Server-side: @GrpcService annotated classes in gamelan-grpc will be automatically instrumented
   - Configuration is controllable via properties: QUARKUS_OTEL_ENABLED and QUARKUS_OTEL_TRACING_ENABLED

  The integration is seamless - when gamelan-engine starts up, it loads the gRPC services from the gamelan-grpc
  module, and with OpenTelemetry enabled, all gRPC communications will be automatically traced without any
  additional configuration needed.