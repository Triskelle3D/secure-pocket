#!/usr/bin/env python3
"""Secure Pocket Link — desktop library server.

Serves a folder of recordings (mp3 + sidecar json/md) over LAN or tailnet.
The phone points Settings > Sinken > Secure Pocket Link at http://<this machine>:8732
and browses/imports the library with zero cloud dependency.

Stdlib only. Binds 0.0.0.0 (LAN + tailscale reachable). Optional shared token:
  --token <secret>   (phone must send ?k=<secret> on every request)
"""
import argparse
import hashlib
import json
import mimetypes
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.expanduser("~/secure-pocket-link/inbox")
TOKEN = None

def scan():
    out = []
    if not os.path.isdir(ROOT):
        return out
    for name in sorted(os.listdir(ROOT)):
        if not name.lower().endswith(".mp3"):
            continue
        path = os.path.join(ROOT, name)
        size = os.path.getsize(path)
        mtime = os.path.getmtime(path)
        entry = {
            "name": name,
            "bytes": size,
            "mtime": int(mtime),
            "sha16": None,
            "sidecar": None,
        }
        base = os.path.splitext(name)[0]
        for ext in (".json", ".md", ".txt"):
            sc = os.path.join(ROOT, base + ext)
            if os.path.isfile(sc):
                entry["sidecar"] = os.path.basename(sc)
                break
        h = hashlib.sha256()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(1 << 20)
                if not chunk:
                    break
                h.update(chunk)
        entry["sha16"] = h.hexdigest()[:16]
        out.append(entry)
    return out

class Handler(BaseHTTPRequestHandler):
    def _check(self, qs):
        if TOKEN and qs.get("k", [""])[0] != TOKEN:
            self.send_response(401)
            self.end_headers()
            return False
        return True

    def do_GET(self):
        u = urlparse(self.path)
        qs = parse_qs(u.query)
        if not self._check(qs):
            return
        if u.path == "/health":
            return self._json(200, {"ok": True, "server": "secure-pocket-link", "root": ROOT})
        if u.path == "/index.json":
            return self._json(200, {"at": int(time.time()), "files": scan()})
        if u.path.startswith("/file/"):
            name = unquote(u.path[len("/file/"):])
            path = os.path.realpath(os.path.join(ROOT, name))
            if not path.startswith(os.path.realpath(ROOT) + os.sep) or not os.path.isfile(path):
                return self._json(404, {"error": "not found"})
            ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(os.path.getsize(path)))
            self.end_headers()
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(1 << 20)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return
        self._json(404, {"error": "unknown path"})

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        sys.stderr.write("[link] %s\n" % (fmt % args))

def main():
    global TOKEN, ROOT
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--port", type=int, default=8732)
    ap.add_argument("--token", default=None)
    a = ap.parse_args()
    ROOT = os.path.expanduser(a.root)
    os.makedirs(ROOT, exist_ok=True)
    TOKEN = a.token
    print(f"secure-pocket-link serving {ROOT} on 0.0.0.0:{a.port}" + (" (token-protected)" if TOKEN else " (open on LAN)"))
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()

if __name__ == "__main__":
    main()
