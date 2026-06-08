#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: $0 <version>"
    echo "  Example: $0 1.0.0"
    echo "  Example: $0 1.0.0-ALPHA"
    exit 1
}

[ $# -eq 1 ] || usage

NEW="$1"

if [[ ! "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9._-]+)?$ ]]; then
    echo "error: '$NEW' does not look like a version (expected MAJOR.MINOR.PATCH[-QUALIFIER])" >&2
    exit 1
fi

ROOT_BUILD="build.gradle.kts"

OLD=$(grep -m1 'version = "' "$ROOT_BUILD" | sed -E 's/.*version = "(.*)".*/\1/')

if [ "$OLD" = "$NEW" ]; then
    echo "Already at $NEW — nothing to do."
    exit 0
fi

sed -i "s/version = \"$OLD\"/version = \"$NEW\"/" "$ROOT_BUILD"
echo "Version updated: $OLD → $NEW"

git add "$ROOT_BUILD"
git commit -m "chore(release): bump version to $NEW"

TAG="v$NEW"
git tag "$TAG"
echo "Tag created: $TAG"

git push origin HEAD "$TAG"
echo "Pushed commit and tag $TAG to origin"
