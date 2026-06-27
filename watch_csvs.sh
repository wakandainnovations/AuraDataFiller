#!/usr/bin/env bash
# Watches /Users/mukundv/Documents/work/space/data_collections for new CSV files
# and runs AuraDataFiller on each one automatically.
#
# Usage:
#   ./watch_csvs.sh              # watch the default data_collections folder
#   ./watch_csvs.sh /other/path  # watch a different folder

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/AuraDataFiller-1.0-SNAPSHOT.jar"
WATCH_DIR="${1:-/Users/mukundv/Documents/work/space/data_collections}"

if [ ! -f "$JAR" ]; then
    echo "JAR not found — building first..."
    cd "$SCRIPT_DIR" && mvn -q package -DskipTests
fi

echo "Starting watcher on: $WATCH_DIR"
java -jar "$JAR" --watch "$WATCH_DIR"
