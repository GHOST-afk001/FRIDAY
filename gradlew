#!/usr/bin/env sh
# Lightweight, source-only Gradle bootstrapper. It keeps this repository free of
# binary wrapper files while downloading the pinned Gradle distribution on demand.
set -eu

GRADLE_VERSION="8.13"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-$GRADLE_VERSION"
GRADLE_HOME="$DIST_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_DIR"
  ARCHIVE="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ARCHIVE" ]; then
    printf '%s\n' "Downloading Gradle $GRADLE_VERSION..."
    curl --fail --location --retry 3 \
      "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
      --output "$ARCHIVE"
  fi
  unzip -q -o "$ARCHIVE" -d "$DIST_DIR"
fi

exec "$GRADLE_BIN" "$@"
