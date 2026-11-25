#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

PAPER_API_VERSION="${PAPER_API_VERSION:-26.3}"
if [[ "$PAPER_API_VERSION" == "26.3" ]]; then
  PAPER_API_COORD="26.3.build.254-stable"
  PLUGIN_API_VERSION="26.3"
elif [[ "$PAPER_API_VERSION" == "1.21.11" || "$PAPER_API_VERSION" == "1.21" ]]; then
  PAPER_API_COORD="1.21.11-R0.1-SNAPSHOT"
  PLUGIN_API_VERSION="1.21"
else
  echo "Unsupported PAPER_API_VERSION: $PAPER_API_VERSION" >&2
  exit 2
fi

mvn -q -Dpaper.api.version="$PAPER_API_COORD" -Dpaper.api-version="$PLUGIN_API_VERSION" clean package
mkdir -p dist
cp "target/HammerCrossServer-1.0.0-${PLUGIN_API_VERSION}.jar" \
   "dist/HammerCrossServer-1.0.0-${PAPER_API_VERSION}.jar"
echo "Built dist/HammerCrossServer-1.0.0-${PAPER_API_VERSION}.jar"
