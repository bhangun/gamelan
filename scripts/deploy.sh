#!/bin/bash

# Gamelan Deployment Utility
# Facilitates multi-module project versioning, git tagging, and artifact deployment.

set -e

NEW_VERSION=""
REPO_URL=""
SNAPSHOT_REPO_URL=""
DRY_RUN=false
SKIP_TESTS=true
GIT_TAG=false
GIT_PUSH=false
BUILD_JAR=true
SKIP_COMMIT=false

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() {
    echo -e "${BLUE}--------------------------------------------------${NC}"
    echo -e "${GREEN} Gamelan Deployment Utility ${NC}"
    echo -e "${BLUE}--------------------------------------------------${NC}"
    echo ""
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  -v, --version <new-version>      Update project version (e.g., 1.0.0 or 1.0.1-SNAPSHOT)"
    echo "  -r, --repo <url>                 Override release repository URL"
    echo "  -s, --snapshot-repo <url>        Override snapshot repository URL"
    echo "  -t, --tag                        Create git tag for the version"
    echo "  -p, --push                       Push git tag to remote"
    echo "  --no-jar                         Skip JAR build"
    echo "  --keep-tests                     Do not skip tests during build"
    echo "  --skip-commit                    Skip git commit after version update"
    echo "  --dry-run                        Show commands without executing them"
    echo "  -h, --help                       Display this help message"
    echo ""
    echo "Examples:"
    echo "  $0 -v 1.0.0                      # Set version to 1.0.0"
    echo "  $0 -v 1.0.0 -t                   # Set version and create git tag"
    echo "  $0 -v 1.0.0 -t -p                # Set version, tag, and push"
    echo "  $0 -v 1.1.0-SNAPSHOT --dry-run   # Dry run with SNAPSHOT version"
    echo ""
    exit 0
}

while [[ "$#" -gt 0 ]]; do
    case $1 in
        -v|--version) NEW_VERSION="$2"; shift ;;
        -r|--repo) REPO_URL="$2"; shift ;;
        -s|--snapshot-repo) SNAPSHOT_REPO_URL="$2"; shift ;;
        -t|--tag) GIT_TAG=true ;;
        -p|--push) GIT_PUSH=true ;;
        --no-jar) BUILD_JAR=false ;;
        --keep-tests) SKIP_TESTS=false ;;
        --skip-commit) SKIP_COMMIT=true ;;
        --dry-run) DRY_RUN=true ;;
        -h|--help) usage ;;
        *) echo -e "${RED}Unknown parameter: $1${NC}"; usage ;;
    esac
    shift
done

cd "$PROJECT_ROOT"

echo -e "${BLUE}--------------------------------------------------${NC}"
echo -e "${GREEN} Gamelan Deployment Utility ${NC}"
echo -e "${BLUE}--------------------------------------------------${NC}"
echo ""

run_cmd() {
    if [ "$DRY_RUN" = true ]; then
        echo -e "${YELLOW}[DRY-RUN]${NC} $*"
    else
        eval "$@"
    fi
}

if [ -n "$NEW_VERSION" ]; then
    echo -e "${BLUE}>>> Updating version to: ${GREEN}$NEW_VERSION${NC}"

    if [[ "$NEW_VERSION" != *"-SNAPSHOT" ]]; then
        echo -e "${YELLOW}ℹ Release version detected (not SNAPSHOT)${NC}"
        GIT_TAG=true
    fi

    echo -e "${BLUE}>>> Updating Maven POMs...${NC}"
    run_cmd "mvn versions:set -DnewVersion=\"$NEW_VERSION\" -DgenerateBackupPoms=false"

    if [ "$SKIP_COMMIT" = false ] && [ "$DRY_RUN" = false ]; then
        echo -e "${BLUE}>>> Committing version changes...${NC}"
        git add "**/pom.xml"
        git commit -m "chore: bump version to $NEW_VERSION" || echo -e "${YELLOW}⚠ No changes to commit or not a git repo${NC}"
    fi

    if [ "$GIT_TAG" = true ]; then
        TAG_NAME="$NEW_VERSION"
        echo -e "${BLUE}>>> Creating git tag: ${GREEN}$TAG_NAME${NC}"

        if git rev-parse "$TAG_NAME" >/dev/null 2>&1; then
            echo -e "${YELLOW}⚠ Tag $TAG_NAME already exists. Skipping tag creation.${NC}"
        else
            run_cmd "git tag -a \"$TAG_NAME\" -m \"Release $NEW_VERSION\""
            echo -e "${GREEN}✓ Git tag created: $TAG_NAME${NC}"
        fi

        if [ "$GIT_PUSH" = true ]; then
            echo -e "${BLUE}>>> Pushing git tag to remote...${NC}"
            run_cmd "git push origin \"$TAG_NAME\""
            echo -e "${GREEN}✓ Git tag pushed: $TAG_NAME${NC}"
        fi
    fi

    echo -e "${GREEN}✓ Version updated to $NEW_VERSION${NC}"
    echo ""
fi

MVN_ARGS="clean deploy"

if [ "$SKIP_TESTS" = true ]; then
    MVN_ARGS="$MVN_ARGS -DskipTests"
fi

if [ "$BUILD_JAR" = false ]; then
    MVN_ARGS="clean"
    echo -e "${YELLOW}⚠ JAR build skipped (--no-jar)${NC}"
fi

if [ -n "$REPO_URL" ]; then
    MVN_ARGS="$MVN_ARGS -Ddeployment.repo.url=$REPO_URL"
fi

if [ -n "$SNAPSHOT_REPO_URL" ]; then
    MVN_ARGS="$MVN_ARGS -Ddeployment.snapshot.repo.url=$SNAPSHOT_REPO_URL"
fi

echo -e "${BLUE}>>> Running deployment: mvn $MVN_ARGS${NC}"
if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}[DRY-RUN]${NC} mvn $MVN_ARGS"
else
    mvn $MVN_ARGS
fi

echo ""
echo -e "${BLUE}--------------------------------------------------${NC}"
echo -e "${GREEN} Deployment process completed!${NC}"
echo -e "${BLUE}--------------------------------------------------${NC}"

if [ -n "$NEW_VERSION" ]; then
    echo ""
    echo -e "${BLUE}Summary:${NC}"
    echo -e "  Version:  ${GREEN}$NEW_VERSION${NC}"
    if [ "$GIT_TAG" = true ]; then
        echo -e "  Git Tag:  ${GREEN}$NEW_VERSION${NC}"
    fi
    if [ "$GIT_PUSH" = true ]; then
        echo -e "  Pushed:   ${GREEN}Yes${NC}"
    fi
    echo ""
fi
