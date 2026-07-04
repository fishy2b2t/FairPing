#!/usr/bin/env bash
set -euo pipefail

JAVA21=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home
JAVA25=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RELEASES="$SCRIPT_DIR/releases"
mkdir -p "$RELEASES"

# minecraft_version | yarn_mappings (or MOJANG) | fabric_version
VERSIONS=(
  "1.21.5  | 1.21.5+build.1  | 0.128.2+1.21.5"
  "1.21.6  | 1.21.6+build.1  | 0.128.2+1.21.6"
  "1.21.7  | 1.21.7+build.8  | 0.129.0+1.21.7"
  "1.21.8  | 1.21.8+build.1  | 0.136.1+1.21.8"
  "1.21.9  | 1.21.9+build.1  | 0.134.1+1.21.9"
  "1.21.10 | 1.21.10+build.3 | 0.138.4+1.21.10"
  "1.21.11 | 1.21.11+build.6 | 0.141.4+1.21.11"
  "26.1    | MOJANG          | 0.154.0+26.1.2"
  "26.1.1  | MOJANG          | 0.154.0+26.1.2"
  "26.1.2  | MOJANG          | 0.154.0+26.1.2"
  "26.2    | MOJANG          | 0.154.0+26.2"
)

ORIG=$(cat "$SCRIPT_DIR/gradle.properties")
PASS=()
FAIL=()

for entry in "${VERSIONS[@]}"; do
  MC=$(echo "$entry"    | awk -F'|' '{gsub(/ /,"",$1); print $1}')
  YARN=$(echo "$entry"  | awk -F'|' '{gsub(/ /,"",$2); print $2}')
  FAPI=$(echo "$entry"  | awk -F'|' '{gsub(/ /,"",$3); print $3}')

  echo ""
  echo "=========================================="
  echo "Building for MC $MC"
  echo "=========================================="

  if [ "$YARN" = "MOJANG" ]; then
    JAVA_VER=25
  else
    JAVA_VER=21
  fi

  cat > "$SCRIPT_DIR/gradle.properties" <<EOF
org.gradle.jvmargs=-Xmx4G -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.workers.max=8
org.gradle.caching=true
minecraft_version=$MC
yarn_mappings=$YARN
loader_version=0.19.3
loom_version=1.17.13
mod_version=1.0.0
maven_group=net.fairping
archives_base_name=fairping
fabric_version=$FAPI
java_version=$JAVA_VER
EOF

  cd "$SCRIPT_DIR"
  if [ "$YARN" = "MOJANG" ]; then
    export JAVA_HOME="$JAVA25"
  else
    export JAVA_HOME="$JAVA21"
  fi
  if ./gradlew build --parallel -q 2>/dev/null; then
    JAR=$(ls build/libs/fairping-*.jar | grep -v sources | head -1)
    DEST="$RELEASES/fairping-1.0.0-mc${MC}.jar"
    cp "$JAR" "$DEST"
    echo "  OK -> releases/fairping-1.0.0-mc${MC}.jar"
    PASS+=("$MC")
  else
    echo "  FAILED"
    FAIL+=("$MC")
  fi
done

# Restore original gradle.properties
echo "$ORIG" > "$SCRIPT_DIR/gradle.properties"

echo ""
echo "=========================================="
echo "DONE"
echo "=========================================="
echo "Passed (${#PASS[@]}): ${PASS[*]:-none}"
echo "Failed (${#FAIL[@]}): ${FAIL[*]:-none}"
