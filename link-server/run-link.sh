#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
exec python3 link-server/secure_pocket_link.py "$@"
