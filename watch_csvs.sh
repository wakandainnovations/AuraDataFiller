#!/usr/bin/env bash
# Watches /Users/mukundv/Documents/work/space/data_collections for new CSV files
# and runs AuraDataFiller on each one automatically.
#
# Usage:
#   ./watch_csvs.sh                                    # watch the default data_collections folder
#   ./watch_csvs.sh /other/path                        # watch a different folder
#   ./watch_csvs.sh --actor-filmography "Actor Name"   # print actor's full filmography
#   ./watch_csvs.sh --actor-filmography "Actor Name" YYYY  # filmography up to a given year

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/AuraDataFiller-1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
    echo "JAR not found — building first..."
    cd "$SCRIPT_DIR" && mvn -q package -DskipTests
fi

if [ "${1:-}" = "--actor-filmography" ]; then
    if [ -z "${2:-}" ]; then
        echo "Usage: $0 --actor-filmography \"Actor Name\" [YYYY]" >&2
        exit 1
    fi
    if [ -n "${3:-}" ]; then
        java -jar "$JAR" --actor-filmography "${2}" "${3}"
    else
        java -jar "$JAR" --actor-filmography "${2}"
    fi
    exit 0
fi

WATCH_DIR="${1:-/Users/mukundv/Documents/work/space/data_collections}"
echo "Starting watcher on: $WATCH_DIR"
java -jar "$JAR" --watch "$WATCH_DIR"
