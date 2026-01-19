# Gamelan Standalone Runtime

A self-contained workflow engine runtime that includes everything needed to run workflows without external dependencies.

## Features

- Complete workflow engine with scheduler and dispatcher
- Built-in local executors
- Embedded database (H2)
- Embedded gRPC server for executor communication
- REST API for workflow management
- Metrics and health checks
- Zero-configuration startup
- Plugin system with upload capability
- Dynamic plugin management

## Quick Start

### Running from JAR

```bash
# Download the latest release
java -jar gamelan-runtime-standalone-{version}-runner.jar

# The server will start with configuration from application.properties
```

### Running with Maven

```bash
# Clone the repository
git clone https://github.com/kayys/gamelan.git
cd gamelan/gamelan-runtime-standalone

# Run in development mode
./mvnw quarkus:dev

# Build and run
./mvnw package
java -jar target/gamelan-runtime-standalone-{version}-runner.jar
```

### Running with Docker

```bash
# Pull and run the latest image
docker run -p 8080:8080 -p 9090:9090 kayys/gamelan-standalone:latest

# Or build and run locally
docker build -t gamelan-standalone .
docker run -p 8080:8080 -p 9090:9090 gamelan-standalone
```

### Running with Docker Compose

```bash
# Start with default configuration
docker-compose up -d

# Stop the service
docker-compose down
```

## Configuration

The standalone runtime can be configured via:

1. **Environment variables**
2. **application.properties** file
3. **Default values**

### Environment Variables

- `QUARKUS_HTTP_PORT`: HTTP port (default: 8080)
- `QUARKUS_GRPC_SERVER_PORT`: gRPC server port (default: 9090)
- `QUARKUS_DATASOURCE_JDBC_URL`: Database connection URL
- `GAMELAN_ENGINE_NAME`: Name of the workflow engine instance
- `GAMELAN_ENGINE_ID`: Unique ID for the engine instance
- `GAMELAN_TENANT_DEFAULT_ID`: Default tenant ID
- `GAMELAN_EXECUTOR_TRANSPORT`: Executor transport type (LOCAL, GRPC, KAFKA, REST)

## API Endpoints

Once running, the following endpoints are available:

- `GET /q/health` - Health check
- `GET /q/metrics` - Prometheus metrics
- `GET /q/swagger-ui` - Interactive API documentation
- `POST /api/workflows` - Create new workflow
- `GET /api/workflows` - List workflows
- `POST /api/runs` - Start workflow run
- `GET /api/runs` - List workflow runs

## Plugin Management

The standalone runtime includes a comprehensive plugin management system:

### Plugin Upload
- `POST /api/plugins/upload` - Upload a new plugin JAR file
- Supports JAR files only
- Plugins are automatically loaded after upload

### Plugin Operations
- `GET /api/plugins` - List all loaded plugins
- `GET /api/plugins/{filename}` - Get information about a specific plugin
- `DELETE /api/plugins/{filename}` - Remove a plugin
- `PUT /api/plugins/{filename}/enable` - Enable a plugin
- `PUT /api/plugins/{filename}/disable` - Disable a plugin
- `POST /api/plugins/refresh` - Rescan and reload plugins

### Plugin Configuration
- `GET /api/plugins/{filename}/config` - Get plugin configuration
- `POST /api/plugins/{filename}/config` - Update plugin configuration
- `PUT /api/plugins/{filename}/config/{key}` - Set a configuration property
- `DELETE /api/plugins/{filename}/config/{key}` - Remove a configuration property

### Plugin Directory
- Default location: `./plugins`
- Configurable via `gamelan.plugins.directory` property
- Automatically scanned for plugins on startup

## Architecture

The standalone runtime includes:

- **Workflow Engine**: Core workflow execution engine
- **Scheduler**: Task scheduling and retry management
- **Dispatcher**: Task dispatching to executors
- **Local Executors**: Built-in executors for common tasks
- **Storage**: Embedded database for workflow state
- **API Layer**: REST and gRPC interfaces

## Security Configuration

The standalone runtime supports TLS/SSL for secure communication. You can generate certificates using the provided script:

```bash
# Generate certificates for TLS/SSL
./scripts/generate-certs.sh
```

The generated certificates will be placed in the `./certs` directory and can be used to configure secure communication.

### TLS Configuration Properties

Add these properties to your `application.properties` to enable TLS:

```properties
# HTTPS Configuration
quarkus.http.ssl.certificate.file=certs/server.crt
quarkus.http.ssl.certificate.key-file=certs/server.key
quarkus.http.ssl.port=8443

# gRPC TLS Configuration
quarkus.grpc.server.ssl.certificate-file=certs/server.crt
quarkus.grpc.server.ssl.key-file=certs/server.key
quarkus.grpc.server.ssl.client-auth=CERTIFICATE
quarkus.grpc.server.ssl.trust-store-file=certs/truststore/truststore.jks
quarkus.grpc.server.ssl.trust-store-password=changeit

# Keystore Configuration
quarkus.tls.trust-store.file=certs/truststore/truststore.jks
quarkus.tls.trust-store.password=changeit
quarkus.tls.key-store.file=certs/keystore/server.jks
quarkus.tls.key-store.password=changeit
```

## Production Considerations

For production use, consider:

- External database instead of embedded H2
- External Redis for distributed caching
- Proper TLS certificates from a trusted CA
- Proper monitoring and alerting
- Backup and recovery procedures
- Resource limits and scaling

## Building from Source

```bash
# Build JAR
./mvnw package

# Build native image (requires GraalVM)
./mvnw package -Pnative

# Build Docker image
docker build -t gamelan-standalone .
```

## Testing

The standalone runtime includes comprehensive tests for all API endpoints:

### Unit Tests
- `WorkflowDefinitionResourceTest` - Tests for workflow definition management
- `WorkflowRunResourceTest` - Tests for workflow run operations
- `ExecutorRegistryResourceTest` - Tests for executor registration and management
- `PluginResourceTest` - Tests for plugin upload and management
- `CallbackResourceTest` - Tests for workflow callbacks and health endpoints

### Integration Tests
- Integration tests (`*IT.java`) that run the full application in a realistic environment

### Running Tests

```bash
# Run all unit tests
./mvnw test

# Run all integration tests
./mvnw verify -DskipITs=false

# Run specific test
./mvnw test -Dtest=WorkflowDefinitionResourceTest

# Run test suite
./mvnw test -Dtest=GamelanRuntimeTestSuite
```

### Test Coverage

The test suite provides:
- API endpoint validation
- Request/response format verification
- Error handling testing
- Integration testing with all components
- End-to-end workflow scenarios

## Contributing

See the main repository for contribution guidelines.

## License

Apache 2.0