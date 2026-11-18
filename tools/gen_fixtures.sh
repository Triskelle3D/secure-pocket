#!/bin/bash
# Deterministic test fixture: 6s 1kHz sine, 16kHz mono, 32kbps CBR MP3 (wire codec).
set -euo pipefail
cd "$(dirname "$0")/.."
/usr/local/bin/ffmpeg -y -loglevel error -f lavfi -i sine=frequency=1000:duration=6 -ar 16000 -ac 1 -b:a 32k app/src/test/resources/fixtures/tone16k.mp3
shasum -a 256 app/src/test/resources/fixtures/tone16k.mp3 > app/src/test/resources/fixtures/fixtures.sha256
cat app/src/test/resources/fixtures/fixtures.sha256
