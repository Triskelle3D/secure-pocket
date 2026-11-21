#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -x .venv/bin/python ]; then
  exec .venv/bin/python link-server/secure_pocket_link.py "$@"
fi
exec python3 link-server/secure_pocket_link.py "$@"
