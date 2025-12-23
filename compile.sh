#!/usr/bin/env bash
set -euo pipefail

# Run from anywhere, but build from the script's directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Basic checks
if [[ ! -f "pom.xml" ]]; then
  echo "Error: pom.xml not found in $SCRIPT_DIR"
  exit 1
fi

echo "Building SorekillTeams..."
mvn -q clean package

# Find the output jar (exclude original/shaded variants if present)
JAR="$(ls -1 target/*.jar 2>/dev/null | grep -vE '(original-|sources|javadoc)' | head -n 1 || true)"

echo
if [[ -n "$JAR" ]]; then
  echo "Build OK!"
  echo "Output: $JAR"
else
  echo "Build finished, but no jar found in target/."
  echo "Check Maven output above."
  exit 2
fi