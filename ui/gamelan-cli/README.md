# gamelan-cli

Gamelan Workflow Engine CLI - A command-line interface for managing Gamelan workflow engine

## Features

- Manage workflow definitions
- Control workflow runs
- Register and manage executors
- Configuration management
- Cross-platform native executables
- Shell auto-completion

## Installation

### Using Package Managers

#### Homebrew (macOS/Linux)
```bash
brew tap kayys/gamelan
brew install gamelan-cli
```

#### Scoop (Windows)
```powershell
scoop bucket add gamelan https://github.com/kayys/gamelan
scoop install gamelan-cli
```

### Download Pre-built Binaries

Download from the [GitHub releases page](https://github.com/kayys/gamelan/releases) for your platform:
- Linux x64: `gamelan-cli-{version}-linux-x64`
- Linux ARM64: `gamelan-cli-{version}-linux-arm64`
- macOS x64: `gamelan-cli-{version}-macos-x64`
- macOS ARM64: `gamelan-cli-{version}-macos-arm64`
- Windows x64: `gamelan-cli-{version}-windows-x64.exe`

### Using JBang (requires Java 21+)
```bash
jbang gamelan@kayys/gamelan --help
```

### Building from Source
```bash
git clone https://github.com/kayys/gamelan.git
cd gamelan/gamelan-cli
./mvnw package -Dquarkus.package.jar.type=uber-jar
java -jar target/gamelan-cli-1.0.0-SNAPSHOT-runner.jar --help
```

## Usage

### Basic Commands

```bash
# Show help
gamelan --help

# List workflow definitions
gamelan workflow-def list

# Register an executor
gamelan executors register my-executor --type worker --comm-type GRPC

# Configure default server address
gamelan config set --property server.address --value myserver:9090
```

### Configuration Management

The CLI supports persistent configuration stored in `~/.gamelan/config.properties`:

```bash
# Initialize configuration with defaults
gamelan config init

# Set configuration properties
gamelan config set --property server.address --value myserver:9090
gamelan config set --property output.format --value json

# Get configuration properties
gamelan config get --property server.address

# List all configuration
gamelan config list
```

### Available Commands

- `workflow-def` - Manage workflow definitions
- `workflow-run` - Manage workflow runs
- `executors` - Manage executors
- `config` - Manage CLI configuration
- `--help` - Show help information
- `--version` - Show version information

## Development

### Running in Development Mode

```bash
./mvnw quarkus:dev
```

### Building Uber JAR

```bash
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

### Building Native Executable

```bash
# Requires GraalVM
./mvnw package -Dnative

# Or build in container
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

### Generating Shell Completion

```bash
# Generate bash completion script
./mvnw compile exec:java -Dexec.mainClass=tech.kayys.gamelan.cli.GenerateCompletion
```

## Deployment with JReleaser

This project uses JReleaser for multi-platform distribution:

- GitHub Releases: Automatic upload of binaries
- Homebrew Tap: Distribution via Homebrew
- Scoop Bucket: Windows package management
- JBang Catalog: Script-based execution
- Native Images: Optimized executables for each platform

### Release Process

1. Update version in `pom.xml`
2. Commit and tag the release
3. Run JReleaser: `./mvnw jreleaser:full-release`

## Contributing

Contributions are welcome! Please submit pull requests or open issues on the GitHub repository.

## License

Apache 2.0
