#!/usr/bin/env bash
set -euo pipefail

REGISTRY="registry.gitlab.com/enimaloc/catapult"
REMOTE="ssh.enimaloc.fr"

VERSION=${1:-$(grep -m1 'version = "' build.gradle.kts | sed -E 's/.*version = "(.*)".*/\1/')}

IMAGES=(
    "api:$REGISTRY/api"
    "web:$REGISTRY/web"
    "maintenance:$REGISTRY/maintenance"
    "nginx-router:$REGISTRY/nginx-router"
)

echo "==> Building images (version: $VERSION)"
docker build -t "$REGISTRY/api:$VERSION"          -t "$REGISTRY/api:latest"          -f catapult-api/Dockerfile .
docker build -t "$REGISTRY/web:$VERSION"          -t "$REGISTRY/web:latest"          -f catapult-web/Dockerfile .
docker build -t "$REGISTRY/maintenance:$VERSION"  -t "$REGISTRY/maintenance:latest"  catapult-maintenance/
docker build -t "$REGISTRY/nginx-router:$VERSION" -t "$REGISTRY/nginx-router:latest" nginx-router/

echo "==> Deploying to $REMOTE"
for entry in "${IMAGES[@]}"; do
    name="${entry%%:*}"
    image="${entry#*:}"
    echo "  -> $name"
    docker save "$image:latest" | ssh -C "$REMOTE" docker load
    docker save "$image:$VERSION" | ssh -C "$REMOTE" docker load
done

echo "==> Done (v$VERSION)"
