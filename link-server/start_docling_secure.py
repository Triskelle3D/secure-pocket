#!/usr/bin/env python3
"""Run Docling Serve and reload it when the TLS helper renews its certificate."""
import os
from pathlib import Path
import signal
import ssl
import subprocess
import sys
import time


CERT = Path("/run/secrets/docling/tls.crt")
KEY = Path("/run/secrets/docling/tls.key")
API_KEY = Path("/run/secrets/docling-api/api.key")
child = None
stopping = False


def stop(_signum, _frame):
    global stopping
    stopping = True
    if child is not None and child.poll() is None:
        child.terminate()


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)


def start():
    key = API_KEY.read_text(encoding="utf-8").strip()
    if len(key) < 32:
        raise SystemExit("Internal Docling API key is missing or invalid")
    env = os.environ.copy()
    env["DOCLING_SERVE_API_KEY"] = key
    args = ["docling-serve", "run", "--ssl-certfile", str(CERT), "--ssl-keyfile", str(KEY)]
    return subprocess.Popen(args, env=env)


def certificate_stamp():
    return (CERT.stat().st_mtime_ns, KEY.stat().st_mtime_ns)


def wait_for_matching_certificate():
    deadline = time.monotonic() + 60
    while True:
        try:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(str(CERT), str(KEY))
            return
        except (OSError, ssl.SSLError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


wait_for_matching_certificate()
stamp = certificate_stamp()
child = start()
while not stopping:
    if child.poll() is not None:
        raise SystemExit(child.returncode)
    time.sleep(5)
    current = certificate_stamp()
    if current != stamp:
        wait_for_matching_certificate()
        child.terminate()
        try:
            child.wait(timeout=30)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
        if not stopping:
            child = start()
            stamp = current
if child is not None and child.poll() is None:
    try:
        child.wait(timeout=30)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait()
sys.exit(child.returncode if child else 0)
