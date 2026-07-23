#!/usr/bin/env bash
#
# Pull Osmosis' "Save logs" files off a connected phone.
#
# The app writes them to its own external files dir when the Save logs toggle is on:
#   /sdcard/Android/data/dev.konraditurbe.osmosis/files/logs/osmosis_<yyyyMMdd_HHmmss>.log
# (newest 5 kept, see core/FileLog.kt). No adb root needed — that path is world-readable.
#
# Usage:
#   ./dump-logs.sh              pull saved logs into ./logs-dump/<serial>_<stamp>/
#   ./dump-logs.sh -l           also capture a full logcat snapshot alongside them
#   ./dump-logs.sh -s <serial>  target a specific device (else the only one attached)
#   ./dump-logs.sh -o <dir>     write somewhere other than ./logs-dump
#
set -euo pipefail

PKG="dev.konraditurbe.osmosis"
REMOTE="/sdcard/Android/data/$PKG/files/logs"
OUTROOT="logs-dump"
SERIAL=""
WANT_LOGCAT=0

while getopts "ls:o:h" opt; do
    case "$opt" in
        l) WANT_LOGCAT=1 ;;
        s) SERIAL="$OPTARG" ;;
        o) OUTROOT="$OPTARG" ;;
        h) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
        *) exit 2 ;;
    esac
done

command -v adb >/dev/null || { echo "adb not on PATH" >&2; exit 1; }

# Git Bash rewrites /sdcard/... into a Windows path before adb ever sees it.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

DEVICES=$("${ADB[@]}" devices | awk 'NR>1 && $2=="device" {print $1}')
COUNT=$(printf '%s\n' "$DEVICES" | grep -c . || true)
if [ "$COUNT" -eq 0 ]; then
    echo "No device. Plug the phone in, unlock it, and accept the USB-debugging prompt." >&2
    exit 1
elif [ "$COUNT" -gt 1 ] && [ -z "$SERIAL" ]; then
    echo "More than one device attached — pick one with -s <serial>:" >&2
    printf '  %s\n' $DEVICES >&2
    exit 1
fi
[ -n "$SERIAL" ] || SERIAL=$(printf '%s\n' "$DEVICES" | head -1)
ADB=(adb -s "$SERIAL")

STAMP=$(date +%Y%m%d_%H%M%S)
OUT="$OUTROOT/${SERIAL}_${STAMP}"
mkdir -p "$OUT"

echo "Device : $SERIAL ($("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r'))"
echo "Source : $REMOTE"

# `adb pull` on a missing dir is a confusing error, so check first and say what to do about it.
if ! "${ADB[@]}" shell "[ -d '$REMOTE' ]" 2>/dev/null; then
    echo
    echo "No logs directory on the device — Save logs has never been enabled." >&2
    echo "Turn it on in the app, reproduce the problem, then re-run this." >&2
    exit 1
fi

"${ADB[@]}" pull -a "$REMOTE/." "$OUT" >/dev/null 2>&1 || true

if [ "$WANT_LOGCAT" -eq 1 ]; then
    echo "Logcat : snapshot -> logcat.txt"
    "${ADB[@]}" logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
fi

FILES=$(find "$OUT" -name 'osmosis_*.log' | sort)
if [ -z "$FILES" ]; then
    echo
    echo "Directory exists but holds no osmosis_*.log — the toggle is on but no session ran yet." >&2
    rmdir "$OUT" 2>/dev/null || true
    exit 1
fi

echo
echo "Pulled into $OUT:"
# shellcheck disable=SC2086
ls -la $FILES | awk '{printf "  %8s  %s\n", $5, $NF}'

NEWEST=$(printf '%s\n' "$FILES" | tail -1)
echo
echo "Newest: $NEWEST"

# The lines worth eyeballing before sending anything on: which datalink port answered is the
# whole point of the unverified-model test builds, and a failed handshake explains an empty grid.
echo "--- datalink / model ---"
grep -E "handshake (OK|FAILED)|actually speaks|HIT \[|~experimental|model=" "$NEWEST" | tail -20 || echo "  (nothing matched)"
echo "--- last 20 lines ---"
tail -20 "$NEWEST"
