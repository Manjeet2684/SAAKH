#!/bin/sh
# Apache Maven Wrapper startup script, version 3.3.2
set -eu

basedir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

distributionUrl=$(tr -d '\r' < "$basedir/.mvn/wrapper/maven-wrapper.properties" | sed -n 's/^distributionUrl=//p')
if [ -z "$distributionUrl" ]; then
  echo "cannot read distributionUrl property in $basedir/.mvn/wrapper/maven-wrapper.properties" >&2
  exit 1
fi

hash=$(printf '%s' "$distributionUrl" | md5sum | awk '{print $1}')
name=$(printf '%s' "$distributionUrl" | sed 's|.*/||; s/\.[^.]*$//; s/-bin$//')
MAVEN_HOME="${HOME}/.m2/wrapper/dists/${name}/${hash}"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  echo "Maven Wrapper: downloading $distributionUrl" >&2
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT INT TERM HUP
  curl -fsSL -o "$tmp/maven.zip" "$distributionUrl"
  unzip -qo "$tmp/maven.zip" -d "$tmp"
  actual=$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)
  mkdir -p "$(dirname "$MAVEN_HOME")"
  rm -rf "$MAVEN_HOME"
  mv "$actual" "$MAVEN_HOME"
  trap - EXIT INT TERM HUP
  rm -rf "$tmp"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
