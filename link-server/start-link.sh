#!/bin/sh
set -eu
BASE=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
if [ "$(uname -s)" = Darwin ]; then
  STATE="$HOME/Library/Application Support/secure-pocket-link"
else
  STATE="${XDG_CONFIG_HOME:-$HOME/.config}/secure-pocket-link"
fi
mkdir -p "$STATE"
PIDFILE="$STATE/link.pid"
LOGFILE="$STATE/link.log"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "Secure Pocket Link is already running (PID $(cat "$PIDFILE"))."
else
  rm -f "$PIDFILE"
  if [ -x "$BASE/dist/SecurePocketLink" ]; then
    nohup "$BASE/dist/SecurePocketLink" </dev/null >"$LOGFILE" 2>&1 &
  else
    nohup python3 -u "$BASE/link-server/secure_pocket_link.py" </dev/null >"$LOGFILE" 2>&1 &
  fi
  echo $! > "$PIDFILE"
  PID=$!
  ready=false
  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    kill -0 "$PID" 2>/dev/null || break
    if ! command -v curl >/dev/null 2>&1 || curl -fsS --max-time 1 http://127.0.0.1:8733/admin/ >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 1
  done
  if [ "$ready" != true ]; then
    kill "$PID" 2>/dev/null || true
    cat "$LOGFILE"
    rm -f "$PIDFILE"
    echo "Secure Pocket Link could not start. Check ports 8732 and 8733." >&2
    exit 1
  fi
  echo "Secure Pocket Link started in the background."
fi
echo "Settings: http://127.0.0.1:8733/admin/"
if command -v open >/dev/null 2>&1; then
  open http://127.0.0.1:8733/admin/ >/dev/null 2>&1 || true
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open http://127.0.0.1:8733/admin/ >/dev/null 2>&1 || true
fi
echo "Stop it with link-server/stop-link.sh. Log: $LOGFILE"
