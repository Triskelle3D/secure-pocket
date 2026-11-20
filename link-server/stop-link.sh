#!/bin/sh
set -eu
if [ "$(uname -s)" = Darwin ]; then
  STATE="$HOME/Library/Application Support/secure-pocket-link"
else
  STATE="${XDG_CONFIG_HOME:-$HOME/.config}/secure-pocket-link"
fi
PIDFILE="$STATE/link.pid"
if [ ! -f "$PIDFILE" ]; then
  echo "Secure Pocket Link is not running (no PID file)."
  exit 0
fi
PID=$(cat "$PIDFILE")
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  echo "Stopped Secure Pocket Link (PID $PID)."
else
  echo "Secure Pocket Link was already stopped."
fi
rm -f "$PIDFILE"
