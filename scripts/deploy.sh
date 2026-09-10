#!/bin/bash

# Gamelan Deployment Utility
# Facilitates multi-module Maven project versioning, git tagging, and artifact deployment.

set -e

# Default values
NEW_VERSION=""
REPO_URL=""
SNAPSHOT_REPO_URL=""
DRY_RUN=false
SKIP_TESTS=true
OFFLINE=false
GIT_TAG=false
GIT_PUSH=true      # Enabled by default
RUN_BUILD=true
SKIP_COMMIT=false
FORCE=false
LOCAL_MAVEN=false
PUBLISH_GITHUB=false
GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-}"  # owner/repo, used to default GitHub Packages URL when available

# Detect Java 25 if on macOS
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_25_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    if [[ -n "${JAVA_25_HOME}" ]]; then
        export JAVA_HOME="${JAVA_25_HOME}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
    fi
fi

# Keep local SSH aliases (e.g. github-bhangun) for convenience, but use the canonical GitHub
# repository URL for remote operations and GitHub API-aware publishing.
canonicalize_github_remote() {
    local remote_url="${1:-}"
    if [[ -z "$remote_url" ]]; then
        return 0
    fi

    remote_url="${remote_url//github-bhangun/github.com}"
    remote_url="${remote_url//github-ngoding/github.com}"
    remote_url="${remote_url//github-*/github.com}"
    echo "$remote_url"
}

resolve_github_repository() {
    local remote_url
    remote_url="$(git config --get remote.origin.url || true)"
    if [[ -z "$remote_url" ]]; then
        echo "${GITHUB_REPOSITORY:-}"
        return 0
    fi

    remote_url="$(canonicalize_github_remote "$remote_url")"
    if [[ "$remote_url" =~ ^git@github\.com:([^/]+)/([^/]+)(\.git)?$ ]]; then
        echo "${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
    elif [[ "$remote_url" =~ ^https://github\.com/([^/]+)/([^/]+?)(\.git)?$ ]]; then
        echo "${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
    else
        echo "${GITHUB_REPOSITORY:-}"
    fi
}

REMOTE_ORIGIN_CANONICAL="$(canonicalize_github_remote "$(git config --get remote.origin.url || true)")"
if [[ -n "$REMOTE_ORIGIN_CANONICAL" ]]; then
    GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-$(resolve_github_repository)}"
fi

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get project root (script is in scripts/ or root)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Detect Maven command
if [[ -x "$PROJECT_ROOT/mvnw" ]]; then
    MVN_CMD="$PROJECT_ROOT/mvnw"
else
    MVN_CMD="mvn"
fi

usage() {
    echo -e "${BLUE}--------------------------------------------------${NC}"
    echo -e "${GREEN} Gamelan Deployment Utility ${NC}"
    echo -e "${BLUE}--------------------------------------------------${NC}"
    echo ""
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  -v, --version <new-version>      Update project version (e.g., 0.1.0 or 1.0.0-SNAPSHOT)"
    echo "  -r, --repo <url>                 Override release repository URL"
    echo "  -s, --snapshot-repo <url>        Override snapshot repository URL"
    echo "  -t, --tag                        Create git tag (auto-enabled for release versions)"
    echo "  --no-push                        Skip pushing commits and tags to remote"
    echo "  -f, --force                      Force actions (e.g., overwrite existing tags)"
    echo "  -o, --offline                    Run Maven in offline mode (-o)"
    echo "  --no-build                       Skip Maven build and deployment"
    echo "  --keep-tests                     Do not skip tests during build"
    echo "  --skip-commit                    Skip git commit after version update"
    echo "  --local-maven, --local           Build and install to local Maven repository (~/.m2)"
    echo "  --github                         Deploy to GitHub Packages (requires GITHUB_REPOSITORY or configured distributionManagement)"
    echo "  --dry-run                        Show commands without executing them"
    echo "  -h, --help                       Display this help message"
    echo ""
    echo "Examples:"
    echo "  $0 -v 0.0.1                      # Version to 0.0.1, build/install, tag, and PUSH"
    echo "  $0 -v 0.0.1 --local-maven -o     # Version to 0.0.1 and install locally in offline mode"
    echo "  $0 -v 0.0.1 --no-push            # Build/deploy and tag locally only"
    echo "  $0 -v 1.0.0-SNAPSHOT             # Version and build/deploy snapshot"
    echo ""
    exit 0
}

# Parse arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        -v|--version) NEW_VERSION="$2"; shift ;;
        -r|--repo) REPO_URL="$2"; shift ;;
        -s|--snapshot-repo) SNAPSHOT_REPO_URL="$2"; shift ;;
        -t|--tag) GIT_TAG=true ;;
        -p|--push) GIT_PUSH=true ;;
        --no-push) GIT_PUSH=false ;;
        -f|--force) FORCE=true ;;
        -o|--offline) OFFLINE=true ;;
        --no-build|--no-jar) RUN_BUILD=false ;;
        --keep-tests) SKIP_TESTS=false ;;
        --skip-commit) SKIP_COMMIT=true ;;
        --local-maven|--local) LOCAL_MAVEN=true ;;
        --github) PUBLISH_GITHUB=true ;;
        --dry-run) DRY_RUN=true ;;
        -h|--help) usage ;;
        *) echo -e "${RED}Unknown parameter: $1${NC}"; usage ;;
    esac
    shift
done

# Navigate to project root
cd "$PROJECT_ROOT"

echo -e "${BLUE}--------------------------------------------------${NC}"
echo -e "${GREEN} Gamelan Deployment Utility ${NC}"
echo -e "${BLUE}--------------------------------------------------${NC}"
echo ""

# Helper function for dry-run execution
run_cmd() {
    if [ "$DRY_RUN" = true ]; then
        echo -e "${YELLOW}[DRY-RUN]${NC} $*"
    else
        eval "$@"
    fi
}

# Version Management
if [ -n "$NEW_VERSION" ]; then
    echo -e "${BLUE}>>> Updating version to: ${GREEN}$NEW_VERSION${NC}"
    
    # Check if version is a release (not SNAPSHOT)
    if [[ "$NEW_VERSION" != *"-SNAPSHOT" ]]; then
        echo -e "${YELLOW}ℹ Release version detected (not SNAPSHOT)${NC}"
        GIT_TAG=true
    fi
    
    # Update Maven POMs
    echo -e "${BLUE}>>> Updating Maven POM configurations...${NC}"
    if [ -f "pom.xml" ]; then
        VERSIONS_ARGS="versions:set -DnewVersion=$NEW_VERSION -DgenerateBackupPoms=false"
        if [ "$OFFLINE" = true ]; then
            VERSIONS_ARGS="$VERSIONS_ARGS -o"
        fi
        if [ "$DRY_RUN" = true ]; then
            echo -e "${YELLOW}[DRY-RUN]${NC} $MVN_CMD $VERSIONS_ARGS"
        else
            $MVN_CMD $VERSIONS_ARGS
        fi
    fi

    # Update Gradle configurations if build.gradle.kts exists
    if [ -f "build.gradle.kts" ]; then
        echo -e "${BLUE}>>> Updating Gradle configurations...${NC}"
        if [ "$DRY_RUN" = true ]; then
            echo -e "${YELLOW}[DRY-RUN]${NC} sed -i.bak -e \"s/extra\\[\\\"gamelanVersion\\\"\\] = \\\".*\\\"/extra\\[\\\"gamelanVersion\\\"\\] = \\\"$NEW_VERSION\\\"/\" build.gradle.kts"
        else
            sed -i.bak -e "s/extra\[\"gamelanVersion\"\] = \".*\"/extra\[\"gamelanVersion\"\] = \"$NEW_VERSION\"/" build.gradle.kts
            rm -f build.gradle.kts.bak
        fi
    fi
    
    # Commit version changes
    COMMIT_SUCCESS=false
    if [ "$SKIP_COMMIT" = false ] && [ "$DRY_RUN" = false ]; then
        echo -e "${BLUE}>>> Committing version changes...${NC}"
        git add pom.xml **/pom.xml 2>/dev/null || true
        if [ -f "build.gradle.kts" ]; then
            git add build.gradle.kts
        fi
        if git commit -m "chore: bump version to $NEW_VERSION"; then
            COMMIT_SUCCESS=true
        else
            echo -e "${YELLOW}⚠ No changes to commit or not a git repo${NC}"
        fi
    fi
    
    # Create git tag if requested
    if [ "$GIT_TAG" = true ]; then
        TAG_NAME="$NEW_VERSION"
        echo -e "${BLUE}>>> Creating git tag: ${GREEN}$TAG_NAME${NC}"
        
        # Check if tag already exists
        if git rev-parse "$TAG_NAME" >/dev/null 2>&1; then
            if [ "$FORCE" = true ]; then
                echo -e "${YELLOW}⚠ Tag $TAG_NAME already exists. Forcing recreation...${NC}"
                run_cmd "git tag -d \"$TAG_NAME\""
                run_cmd "git tag -a \"$TAG_NAME\" -m \"Release $NEW_VERSION\""
                echo -e "${GREEN}✓ Git tag force-recreated: $TAG_NAME${NC}"
            else
                echo -e "${YELLOW}⚠ Tag $TAG_NAME already exists. Skipping tag creation (use -f to force).${NC}"
            fi
        else
            run_cmd "git tag -a \"$TAG_NAME\" -m \"Release $NEW_VERSION\""
            echo -e "${GREEN}✓ Git tag created: $TAG_NAME${NC}"
        fi
    fi

    # Push to remote if enabled
    if [ "$GIT_PUSH" = true ] && [ "$DRY_RUN" = false ]; then
        CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "main")
        REMOTE_PUSH_URL="$(git config --get remote.origin.url || true)"
        if [[ -z "$REMOTE_PUSH_URL" ]]; then
            REMOTE_PUSH_URL="origin"
        fi
        SSH_KEY_PATH="${SSH_KEY_PATH:-$HOME/.ssh/id_rsa_bhangun}"
        if [[ -f "$SSH_KEY_PATH" ]]; then
            export GIT_SSH_COMMAND="ssh -i \"$SSH_KEY_PATH\" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
        fi
        echo -e "${BLUE}>>> Pushing to remote (branch: $CURRENT_BRANCH, url: $REMOTE_PUSH_URL)...${NC}"

        if [[ "$REMOTE_PUSH_URL" == git@* || "$REMOTE_PUSH_URL" == https://* ]]; then
            run_cmd "git push \"$REMOTE_PUSH_URL\" $CURRENT_BRANCH" || true
        else
            run_cmd "git push origin $CURRENT_BRANCH" || true
        fi

        # Push the tag if it was created
        if [ "$GIT_TAG" = true ]; then
            echo -e "${BLUE}>>> Pushing git tag to remote...${NC}"
            if [[ "$REMOTE_PUSH_URL" == git@* || "$REMOTE_PUSH_URL" == https://* ]]; then
                if [ "$FORCE" = true ]; then
                    run_cmd "git push \"$REMOTE_PUSH_URL\" :refs/tags/\"$TAG_NAME\" || true"
                    run_cmd "git push \"$REMOTE_PUSH_URL\" \"$TAG_NAME\"" || true
                else
                    run_cmd "git push \"$REMOTE_PUSH_URL\" \"$TAG_NAME\"" || true
                fi
            else
                if [ "$FORCE" = true ]; then
                    run_cmd "git push origin :refs/tags/\"$TAG_NAME\" || true"
                    run_cmd "git push origin \"$TAG_NAME\"" || true
                else
                    run_cmd "git push origin \"$TAG_NAME\"" || true
                fi
            fi
        fi
        echo -e "${GREEN}✓ Push step completed${NC}"
    fi
    
    echo -e "${GREEN}✓ Version updated to $NEW_VERSION${NC}"
    echo ""
fi

# Build and Deployment
if [ "$RUN_BUILD" = true ]; then
    if [ "$PUBLISH_GITHUB" = true ]; then
        MVN_ARGS="clean deploy"
    elif [ "$LOCAL_MAVEN" = true ]; then
        MVN_ARGS="clean install"
    else
        # Default: install to local repository
        MVN_ARGS="clean install"
    fi

    if [ "$SKIP_TESTS" = true ]; then
        MVN_ARGS="$MVN_ARGS -DskipTests -Dmaven.test.skip=true"
    fi

    if [ "$OFFLINE" = true ]; then
        MVN_ARGS="$MVN_ARGS -o"
    fi

    if [ -n "$REPO_URL" ]; then
        MVN_ARGS="$MVN_ARGS -DaltReleaseDeploymentRepository=release-repo::default::$REPO_URL"
    fi

    if [ -n "$SNAPSHOT_REPO_URL" ]; then
        MVN_ARGS="$MVN_ARGS -DaltSnapshotDeploymentRepository=snapshot-repo::default::$SNAPSHOT_REPO_URL"
    fi

    echo -e "${BLUE}>>> Running Maven build/deployment: $MVN_CMD $MVN_ARGS${NC}"
    if [ "$DRY_RUN" = true ]; then
        echo -e "${YELLOW}[DRY-RUN]${NC} $MVN_CMD $MVN_ARGS"
    else
        $MVN_CMD $MVN_ARGS
    fi
else
    echo -e "${YELLOW}⚠ Maven build and deployment skipped (--no-build)${NC}"
fi

echo ""
echo -e "${BLUE}--------------------------------------------------${NC}"
echo -e "${GREEN} Deployment process completed!${NC}"
echo -e "${BLUE}--------------------------------------------------${NC}"

# Summary
if [ -n "$NEW_VERSION" ]; then
    echo ""
    echo -e "${BLUE}Summary:${NC}"
    echo -e "  Version:  ${GREEN}$NEW_VERSION${NC}"
    if [ "$GIT_TAG" = true ]; then
        echo -e "  Git Tag:  ${GREEN}$NEW_VERSION${NC}"
    fi
    echo -e "  Pushed:   $( [ "$GIT_PUSH" = true ] && echo -e "${GREEN}Yes${NC}" || echo -e "${YELLOW}No${NC}" )"
    if [ "$RUN_BUILD" = true ]; then
        echo -e "  Build:    ${GREEN}$MVN_CMD $MVN_ARGS${NC}"
    else
        echo -e "  Build:    ${YELLOW}Skipped${NC}"
    fi
    echo ""
fi
