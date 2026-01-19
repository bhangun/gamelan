# Installation Scripts

This directory contains scripts to help with installing and managing the Gamelan CLI.

## Installation Script

The `install-cli.sh` script provides an easy way to install the Gamelan CLI on your system.

### Usage

```bash
# Install the latest version
./scripts/install-cli.sh

# Install a specific version
./scripts/install-cli.sh --version 1.0.0

# Install to a custom directory
./scripts/install-cli.sh --dir ~/bin

# Show help
./scripts/install-cli.sh --help
```

### Prerequisites

- Java 21 or higher
- curl
- bash

### What the script does

1. Checks for prerequisites
2. Downloads the Gamelan CLI JAR file (or builds from source in development)
3. Creates a wrapper script for easy execution
4. Installs both files to the specified directory
5. Sets appropriate permissions

### Notes

- The script requires write permissions to the installation directory
- If the installation directory is not in your PATH, you'll need to add it manually
- In a production environment, the script would download from a repository; in development, it builds from source