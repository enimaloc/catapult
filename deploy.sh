#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-registry.gitlab.com/enimaloc/catapult}"
REMOTE="${REMOTE:-ssh.enimaloc.fr}"

VERSION=${1:-$(grep -m1 'version = "' build.gradle.kts | sed -E 's/.*version = "(.*)".*/\1/')}

# Build image name: "registry/name" when registry is set, just "name" otherwise
img() {
    local name="$1"
    if [ -n "$REGISTRY" ]; then
        echo "$REGISTRY/$name"
    else
        echo "$name"
    fi
}

SERVICES=(
    "api:catapult-api/Dockerfile:."
    "web:catapult-web/Dockerfile:."
    "maintenance::catapult-maintenance"
    "nginx-router::nginx-router"
)

echo "==> Building images (version: $VERSION)"
for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    rest="${entry#*:}"
    dockerfile="${rest%%:*}"
    context="${rest#*:}"
    image="$(img "$name")"

    if [ -n "$dockerfile" ]; then
        docker build -t "$image:$VERSION" -t "$image:latest" -f "$dockerfile" "$context"
    else
        docker build -t "$image:$VERSION" -t "$image:latest" "$context"
    fi
done

echo "==> Deploying to $REMOTE"
for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    image="$(img "$name")"
    echo "  -> $name"
    docker save "$image:latest"   | ssh -C "$REMOTE" docker load
    docker save "$image:$VERSION" | ssh -C "$REMOTE" docker load
done

echo "==> Done (v$VERSION)"
