#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOTLIN_COMPILER_SERVER_DIR="$HOME/Development/Personal/kotlin-compiler-server"
CONTAINER_NAME="kotlin-compiler-server"
IMAGE_NAME="kotlin-compiler-server"

# Build the Lighting7 jar first
echo "Building Lighting7 jar..."
cd "$SCRIPT_DIR"
./gradlew jar

# Change to kotlin-compiler-server directory
echo "Setting up kotlin-compiler-server..."
cd "$KOTLIN_COMPILER_SERVER_DIR"

# Bail if there are uncommitted changes (we'll be modifying files and using git checkout to clean up)
if [ -n "$(git status --porcelain)" ]; then
    echo "Error: kotlin-compiler-server has uncommitted changes. Please commit or stash them first."
    exit 1
fi

# Create lighting-libs directory and copy jar
mkdir -p lighting-libs/
cp "$SCRIPT_DIR/build/libs/Lighting7-0.0.1.jar" lighting-libs/

# Apply changes directly (more robust than patch file)
echo "Applying customizations..."

# Verify patterns exist before modifying
grep -q '^ENV PORT=8080$' Dockerfile || { echo "Error: Could not find 'ENV PORT=8080' in Dockerfile"; exit 1; }
grep -q '\${PORT}' Dockerfile || { echo "Error: Could not find '\${PORT}' in Dockerfile"; exit 1; }
grep -q 'kotlinWasmDependency(libs\.kotlin\.stdlib\.wasm\.js)' dependencies/build.gradle.kts || { echo "Error: Could not find kotlinWasmDependency line in dependencies/build.gradle.kts"; exit 1; }
grep -q 'val additionalCompilerArguments: List<String> = listOf(' common/src/main/kotlin/component/KotlinEnvironment.kt || { echo "Error: Could not find additionalCompilerArguments in KotlinEnvironment.kt"; exit 1; }

# KotlinEnvironment.kt: Add -jvm-target 17 to additionalCompilerArguments
sed -i '' '/val additionalCompilerArguments: List<String> = listOf(/a\
\      "-jvm-target", "17",
' common/src/main/kotlin/component/KotlinEnvironment.kt

# Dockerfile: ENV PORT=8080 -> EXPOSE 8080/tcp
sed -i '' 's/^ENV PORT=8080$/EXPOSE 8080\/tcp/' Dockerfile

# Dockerfile: ${PORT} -> 8080
sed -i '' 's/\${PORT}/8080/g' Dockerfile

# dependencies/build.gradle.kts: add Lighting7 jar dependency
sed -i '' '/kotlinWasmDependency(libs\.kotlin\.stdlib\.wasm\.js)/a\
\
    kotlinDependency(files("/kotlin-compiler-server/lighting-libs/Lighting7-0.0.1.jar"))
' dependencies/build.gradle.kts

# Build the docker image with captured timestamp
echo "Building docker image..."
IMAGE_TAG=$(date +%s)
grep -q 'my-image-name' docker-image-build.sh || { echo "Error: Could not find 'my-image-name' in docker-image-build.sh"; exit 1; }
grep -q '\$(date +%s)' docker-image-build.sh || { echo "Error: Could not find '\$(date +%s)' in docker-image-build.sh"; exit 1; }
sed -i '' "s/my-image-name/$IMAGE_NAME/" docker-image-build.sh
sed -i '' "s/\$(date +%s)/$IMAGE_TAG/" docker-image-build.sh
./docker-image-build.sh

# Cleanup build artifacts
echo "Cleaning up build artifacts..."
git checkout -- .
rm -rf lighting-libs/

# Stop and remove existing container if it exists
echo "Stopping existing container (if any)..."
docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true

# Run the new container
echo "Starting new container..."
docker run -d -p 8321:8080 --name "$CONTAINER_NAME" --restart unless-stopped "$IMAGE_NAME:$IMAGE_TAG"

# Remove old images (keep only the one we just built)
echo "Cleaning up old images..."
docker images "$IMAGE_NAME" --format '{{.Tag}}' | grep -v "^$IMAGE_TAG$" | xargs -r -I {} docker rmi "$IMAGE_NAME:{}" 2>/dev/null || true

echo "Done! Container '$CONTAINER_NAME' is running with image '$IMAGE_NAME:$IMAGE_TAG'"
