#!/usr/bin/env bash
set -euo pipefail
VERSION="9.8.2"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/sepand-wrapper/gradle-$VERSION"
if [[ ! -x "$CACHE/bin/gradle" ]]; then
  mkdir -p "$(dirname "$CACHE")"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$TMP/gradle.zip"
  unzip -q "$TMP/gradle.zip" -d "$TMP"
  rm -rf "$CACHE"
  mv "$TMP/gradle-$VERSION" "$CACHE"
fi
exec "$CACHE/bin/gradle" "$@"
