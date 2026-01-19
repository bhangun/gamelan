#!/bin/bash

# Gamelan CLI Installation Script
# This script downloads and installs the Gamelan CLI tool

set -e

# Default values
VERSION="latest"
INSTALL_DIR="/usr/local/bin"
TEMP_DIR=$(mktemp -d)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to display usage
usage() {
    echo "Usage: $0 [OPTIONS]"
    echo "Options:"
    echo "  -v, --version VERSION    Specify version to install (default: latest)"
    echo "  -d, --dir DIRECTORY      Specify installation directory (default: /usr/local/bin)"
    echo "  -h, --help               Show this help message"
    exit 1
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        -d|--dir)
            INSTALL_DIR="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            print_error "Unknown option: $1"
            usage
            ;;
    esac
done

print_info "Gamelan CLI Installation Script"
print_info "Version: $VERSION"
print_info "Installation directory: $INSTALL_DIR"

# Check if curl is installed
if ! command -v curl &> /dev/null; then
    print_error "curl is required but not installed. Please install curl first."
    exit 1
fi

# Check if java is installed
if ! command -v java &> /dev/null; then
    print_error "Java is required but not installed. Please install Java 21 or higher first."
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
JAVA_MAJOR_VERSION=$(echo "$JAVA_VERSION" | cut -d'.' -f1)

if [ "$JAVA_MAJOR_VERSION" -lt 21 ]; then
    print_error "Java 21 or higher is required. Current version: $JAVA_VERSION"
    exit 1
fi

# Check if installation directory exists
if [ ! -d "$INSTALL_DIR" ]; then
    print_warning "Installation directory does not exist: $INSTALL_DIR"
    read -p "Create directory? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        sudo mkdir -p "$INSTALL_DIR"
    else
        print_error "Installation cancelled."
        exit 1
    fi
fi

# Check write permissions for installation directory
if [ ! -w "$INSTALL_DIR" ]; then
    print_warning "No write permission for $INSTALL_DIR, will use sudo"
    USE_SUDO="true"
else
    USE_SUDO="false"
fi

# Determine the artifact name based on version
if [ "$VERSION" = "latest" ]; then
    ARTIFACT_NAME="gamelan-cli-*.jar"
else
    ARTIFACT_NAME="gamelan-cli-$VERSION.jar"
fi

print_info "Downloading Gamelan CLI..."

# In a real scenario, this would download from an actual repository
# For now, we'll simulate the download process
# This is a placeholder - in a real scenario, you would have a URL like:
# https://repo.example.com/gamelan/gamelan-cli-$VERSION.jar

# Since we don't have a real repository, let's create a placeholder
# In a real scenario, you would use:
# curl -L -o "$TEMP_DIR/gamelan-cli.jar" "https://repo.example.com/gamelan/gamelan-cli-$VERSION.jar"

print_warning "This script is a template. In a real deployment, it would download from a repository."
print_info "Building from source instead..."

# Build the CLI from source
if [ -d "gamelan-cli" ]; then
    cd gamelan-cli
    mvn clean package -DskipTests
    cd ..
    
    # Copy the built jar to temp directory
    cp gamelan-cli/target/gamelan-cli-*.jar "$TEMP_DIR/gamelan-cli.jar"
else
    print_error "gamelan-cli module not found in current directory. Please run this script from the project root."
    exit 1
fi

print_info "Download complete."

# Create the executable script
cat > "$TEMP_DIR/gamelan" << 'EOF'
#!/bin/bash

# Gamelan CLI wrapper script
# This script provides a convenient way to run the Gamelan CLI

CLI_JAR="$(dirname "$0")/gamelan-cli.jar"

if [ ! -f "$CLI_JAR" ]; then
    echo "Error: gamelan-cli.jar not found in $(dirname "$0")"
    exit 1
fi

exec java -jar "$CLI_JAR" "$@"
EOF

# Make the script executable
chmod +x "$TEMP_DIR/gamelan"

# Install the files
if [ "$USE_SUDO" = "true" ]; then
    sudo cp "$TEMP_DIR/gamelan-cli.jar" "$INSTALL_DIR/"
    sudo cp "$TEMP_DIR/gamelan" "$INSTALL_DIR/"
else
    cp "$TEMP_DIR/gamelan-cli.jar" "$INSTALL_DIR/"
    cp "$TEMP_DIR/gamelan" "$INSTALL_DIR/"
fi

# Clean up
rm -rf "$TEMP_DIR"

print_info "Gamelan CLI installed successfully!"
print_info "You can now run 'gamelan --help' to get started."
print_info "Installation location: $INSTALL_DIR/gamelan"

# Check if INSTALL_DIR is in PATH
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
    print_warning "$INSTALL_DIR is not in your PATH."
    print_info "To add it to your PATH, add the following line to your shell profile:"
    print_info "  export PATH=\$PATH:$INSTALL_DIR"
fi