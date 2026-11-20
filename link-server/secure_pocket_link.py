#!/usr/bin/env python3
"""Secure Pocket Link — desktop library server and localhost settings UI.

Serves a folder of recordings (mp3 + sidecar json/md) over LAN or tailnet.
The phone points Settings > Library Location > Secure Pocket Link at http://<this machine>:8732
and browses/imports the library with zero cloud dependency.

Stdlib only. The phone API binds 0.0.0.0 (LAN + tailscale reachable) and always
requires the shared token shown in the localhost settings page.
"""
import argparse
from collections import deque
from datetime import datetime, timezone
import secrets
import hashlib
import json
import mimetypes
import os
import socket
import sys
import time
import hmac
import re
import shutil
import tempfile
import threading
import zipfile
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ROOT = os.path.expanduser("~/secure-pocket-link/inbox")
TOKEN = None
CONFIG = {}
CONFIG_PATH = None
ADMIN_PORT = 8733
PUBLIC_API_PORT = 8732
PUBLIC_ADMIN_PORT = 8733
PUBLIC_HOST_REQUIRED = False
DOCLING_CONFIG = {}
DOCLING_SLOT = threading.BoundedSemaphore(1)
CONFIG_LOCK = threading.RLock()
LIBRARY_WRITE_LOCK = threading.RLock()
BUNDLE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}-[a-f0-9]{8}\.zip")
STARTED_AT = time.time()
DIAGNOSTIC_LOG = deque(maxlen=200)
DIAGNOSTIC_LOCK = threading.Lock()
LAST_ACTIVITY = None


def _timestamp(value=None):
    return datetime.fromtimestamp(value or time.time(), timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _library_count():
    """Count recordings from directory entries only; status checks must not hash audio."""
    count = 0
    try:
        with os.scandir(ROOT) as entries:
            count += sum(1 for entry in entries if entry.is_file() and entry.name.lower().endswith(".mp3"))
    except OSError:
        return 0
    library = Path(ROOT) / "library"
    try:
        with os.scandir(library) as entries:
            count += sum(1 for entry in entries if entry.is_file() and BUNDLE.fullmatch(entry.name))
    except OSError:
        pass
    return count


def _record_log(level, method, endpoint, status, message):
    global LAST_ACTIVITY
    item = {"at": _timestamp(), "level": level, "method": method,
            "endpoint": endpoint, "status": int(status), "message": message}
    with DIAGNOSTIC_LOCK:
        DIAGNOSTIC_LOG.appendleft(item)
        if not endpoint.startswith("/admin/"):
            LAST_ACTIVITY = item["at"]


def _log_endpoint(path):
    if path in ("/health", "/index.json", "/library/import", "/docling/pre", "/docling/post",
                "/admin/api/settings", "/admin/api/docling-check"):
        return path
    if path.startswith("/library/"):
        return "/library/{bundle}"
    if path.startswith("/file/"):
        return "/file/{item}"
    if path.startswith("/admin/"):
        return "/admin/{other}"
    return "/other"


def _log_method(method):
    method = str(method or "").upper()
    return method if method in ("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS") else "OTHER"


def _docling_probe(config):
    """Make one bounded request to Docling's health endpoint; never return its URL."""
    if not config.get("enabled"):
        return {"state": "disabled", "checkedAt": None}
    base = str(config.get("docling_url") or "http://docling:5001").strip().rstrip("/")
    if not base.startswith(("http://", "https://")):
        return {"state": "unreachable", "checkedAt": _timestamp(), "detail": "Invalid service address"}
    try:
        request = Request(base + "/health", headers={"Accept": "application/json"}, method="GET")
        with urlopen(request, timeout=2.5) as response:
            return {"state": "reachable" if 200 <= response.status < 300 else "unreachable",
                    "httpStatus": response.status, "checkedAt": _timestamp()}
    except HTTPError as exc:
        return {"state": "degraded", "httpStatus": int(exc.code), "checkedAt": _timestamp(),
                "detail": "Service replied, but its health check did not succeed"}
    except (URLError, TimeoutError, OSError, ValueError):
        return {"state": "unreachable", "checkedAt": _timestamp(), "detail": "No response within 2.5 seconds"}


def stream_sha(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(65536), b""):
        digest.update(chunk)
    return digest.hexdigest()

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
    bundles = Path(ROOT) / "library"
    if bundles.is_dir():
        for path in sorted(bundles.glob("*.zip")):
            try:
                with zipfile.ZipFile(path) as z:
                    audio = next(i for i in z.infolist() if i.filename.endswith(".mp3"))
                    with z.open(audio) as f:
                        sha = stream_sha(f)
                    out.append({"name": audio.filename, "bundle": path.name, "bytes": audio.file_size,
                                "mtime": int(path.stat().st_mtime), "sha16": sha[:16], "sidecar": None})
            except (OSError, StopIteration, zipfile.BadZipFile):
                continue
    return out

class Handler(BaseHTTPRequestHandler):
    def _check(self, qs):
        if TOKEN and not hmac.compare_digest(self.headers.get("X-Pocket-Token", qs.get("k", [""])[0]).encode(), TOKEN.encode()):
            self.send_response(401)
            self.end_headers()
            return False
        return True

    def _write_access(self):
        if not TOKEN:
            self._json(403, {"error": "A token is required. Configure it in Secure Pocket Link settings."})
            return False
        return self._check({})

    def _copy_body(self, destination, limit):
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = 0
        if not 0 < size <= limit:
            self._json(413, {"error": "Invalid upload size"})
            return False
        with destination.open("wb") as stream:
            while size:
                chunk = self.rfile.read(min(size, 65536))
                if not chunk:
                    raise OSError("Upload ended early")
                stream.write(chunk)
                size -= len(chunk)
        return True

    def do_PUT(self):
        path = urlparse(getattr(self, "path", "")).path
        name = path.removeprefix("/library/")
        if not path.startswith("/library/") or not BUNDLE.fullmatch(name):
            return self._json(404, {"error": "Unknown library path"})
        if not self._write_access():
            return
        directory = Path(ROOT) / "library"
        directory.mkdir(exist_ok=True)
        fd, temp = tempfile.mkstemp(dir=directory)
        os.close(fd)
        try:
            if not self._copy_body(Path(temp), 1024**3):
                return
            with zipfile.ZipFile(temp) as bundle:
                content = bundle.infolist()
                valid_names = all(not i.is_dir() and len(i.filename) <= 110 and
                                  "/" not in i.filename and "\\" not in i.filename and
                                  (i.filename.endswith((".mp3", ".transcript.md", ".summary.md")) or i.filename == "title.txt") and
                                  (i.filename.endswith(".mp3") or i.file_size <= 2 * 1024**2)
                                  for i in content)
                if (not valid_names or not 1 <= len(content) <= 4
                    or sum(i.filename.endswith(".mp3") for i in content) != 1
                    or sum(i.file_size for i in content) > 1024**3):
                    return self._json(400, {"error": "Invalid library bundle"})
                incoming_audio = next(i for i in content if i.filename.endswith(".mp3"))
                with bundle.open(incoming_audio) as stream:
                    incoming_sha = stream_sha(stream)
            destination = directory / name
            with LIBRARY_WRITE_LOCK:
                already_present = destination.is_file()
                if already_present:
                    try:
                        with zipfile.ZipFile(destination) as current:
                            existing = current.infolist()
                            existing_audio = next(i for i in existing if i.filename.endswith(".mp3"))
                            with current.open(existing_audio) as stream:
                                existing_sha = stream_sha(stream)
                        if not hmac.compare_digest(existing_sha, incoming_sha):
                            return self._json(409, {"error": "A different recording already uses this bundle name"})
                    except (OSError, zipfile.BadZipFile, StopIteration):
                        return self._json(409, {"error": "Existing library bundle is invalid"})
                os.replace(temp, destination)
            self._json(200 if already_present else 201, {"ok": True, "alreadyPresent": already_present})
        except (OSError, zipfile.BadZipFile):
            self._json(400, {"error": "Incomplete library bundle"})
        finally:
            Path(temp).unlink(missing_ok=True)

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/admin/api/settings":
            if not getattr(self.server, "is_admin", False):
                return self._json(404, {"error": "Unknown path"})
            return self._admin_save()
        if path == "/admin/api/docling-check":
            if not getattr(self.server, "is_admin", False):
                return self._json(404, {"error": "Unknown path"})
            return self._admin_docling_check()
        if path == "/library/import":
            return self._import_recording()
        if path not in ("/docling/pre", "/docling/post"):
            return self._json(404, {"error": "Unknown path"})
        if not self._write_access():
            return
        if not DOCLING_CONFIG.get("enabled"):
            return self._json(503, {"error": "Docling is disabled in Secure Pocket Link settings"})
        with DOCLING_SLOT, tempfile.TemporaryDirectory(prefix="pocket-docling-") as workspace:
            audio = path.endswith("/pre")
            source = Path(workspace) / ("recording.mp3" if audio else "summary.md")
            if not self._copy_body(source, 512 * 1024**2 if audio else 2 * 1024**2):
                return
            try:
                from docling_client import process_docling
                result = process_docling(source, kind="audio" if audio else "markdown", config=DOCLING_CONFIG)
                payload = {"markdown": result["markdown"]}
                if result.get("segments") is not None:
                    payload["segments"] = result["segments"]
                return self._json(200, payload)
            except Exception as exc:
                sys.stderr.write("[link] Docling processing failed\n")
                return self._json(502, {"error": "Docling processing failed. Check its settings and model files."})

    def _import_recording(self):
        if not self._write_access():
            return
        day = self.headers.get("X-Recording-Date", "")
        start = self.headers.get("X-Recording-Start", "")
        wire_id = self.headers.get("X-Recording-Wire-Id", "")
        expected_sha = self.headers.get("X-Audio-SHA256", "").lower()
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day) or not re.fullmatch(r"\d{6}", start):
            return self._json(400, {"error": "Invalid recording date or start time"})
        try:
            time.strptime(f"{day} {start}", "%Y-%m-%d %H%M%S")
        except ValueError:
            return self._json(400, {"error": "Invalid recording date or start time"})
        if not re.fullmatch(r"(?:\d{14}|PH\d{12})", wire_id):
            return self._json(400, {"error": "Invalid Pocket recording id"})
        if not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
            return self._json(400, {"error": "Invalid audio SHA-256"})

        directory = Path(ROOT) / "library"
        directory.mkdir(parents=True, exist_ok=True)
        bundle_name = f"{day}-{start}-{expected_sha[:8]}.zip"
        destination = directory / bundle_name
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = 0
        if not 0 < size <= 1024**3:
            return self._json(413, {"error": "Invalid recording size"})
        fd, audio_path = tempfile.mkstemp(dir=directory, prefix="incoming-")
        os.close(fd)
        bundle_path = None
        try:
            if not self._copy_body(Path(audio_path), 1024**3):
                return
            with open(audio_path, "rb") as stream:
                actual_sha = stream_sha(stream)
            if not hmac.compare_digest(actual_sha, expected_sha):
                return self._json(422, {"error": "Audio SHA-256 did not match"})
            with LIBRARY_WRITE_LOCK:
                if destination.is_file():
                    try:
                        with zipfile.ZipFile(destination) as bundle:
                            audio = next(i for i in bundle.infolist() if i.filename.endswith(".mp3"))
                            with bundle.open(audio) as stream:
                                existing_sha = stream_sha(stream)
                        if not hmac.compare_digest(existing_sha, expected_sha):
                            return self._json(409, {"error": "Recording id already exists with different audio"})
                        return self._json(200, {"ok": True, "bundle": bundle_name,
                                                "sha256": expected_sha, "alreadyPresent": True})
                    except (OSError, zipfile.BadZipFile, StopIteration):
                        return self._json(409, {"error": "Existing recording bundle is invalid"})
                fd, bundle_path = tempfile.mkstemp(dir=directory, prefix="bundle-", suffix=".zip")
                os.close(fd)
                recording_name = f"{day}_{start}.mp3"
                with zipfile.ZipFile(bundle_path, "w", compression=zipfile.ZIP_STORED) as bundle:
                    bundle.write(audio_path, recording_name)
                    bundle.writestr("title.txt", f"Recording {day} {start[0:2]}:{start[2:4]}")
                os.replace(bundle_path, destination)
                bundle_path = None
                return self._json(201, {"ok": True, "bundle": bundle_name,
                                        "sha256": expected_sha, "alreadyPresent": False})
        except (OSError, zipfile.BadZipFile):
            return self._json(500, {"error": "Could not store recording"})
        finally:
            Path(audio_path).unlink(missing_ok=True)
            if bundle_path:
                Path(bundle_path).unlink(missing_ok=True)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path.startswith("/admin/"):
            if not getattr(self.server, "is_admin", False):
                return self._json(404, {"error": "Unknown path"})
            if not self._admin_request_allowed():
                return self._json(403, {"error": "Settings are available only from this computer"})
            if u.path == "/admin/":
                return self._admin_page()
            if u.path == "/admin/api/settings":
                return self._admin_settings()
            if u.path == "/admin/api/status":
                return self._admin_status()
            if u.path == "/admin/api/logs":
                return self._admin_logs()
            return self._json(404, {"error": "Unknown settings path"})
        qs = parse_qs(u.query)
        if not self._check(qs):
            return
        if u.path == "/health":
            return self._json(200, {"ok": True, "server": "secure-pocket-link", "root": ROOT,
                                    "authenticated": bool(TOKEN), "writable": bool(TOKEN) and os.access(ROOT, os.W_OK),
                                    "backupConfigured": bool(ROOT and os.path.isdir(ROOT)),
                                    "docling": bool(DOCLING_CONFIG.get("enabled"))})
        if u.path == "/index.json":
            return self._json(200, {"at": int(time.time()), "files": scan()})
        if u.path.startswith("/library/"):
            name = u.path.removeprefix("/library/")
            if not BUNDLE.fullmatch(name):
                return self._json(404, {"error": "Unknown library bundle"})
            path = Path(ROOT) / "library" / name
            if not path.is_file():
                return self._json(404, {"error": "Missing library bundle"})
            with path.open("rb") as f:
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(os.fstat(f.fileno()).st_size))
                self.end_headers()
                shutil.copyfileobj(f, self.wfile)
            return
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

    def _admin_request_allowed(self, data_request=False):
        """Require a literal loopback Host and same-origin browser mutations."""
        import ipaddress
        bound_loopback = self.server.server_address[0] in ("127.0.0.1", "::1", "localhost")
        if bound_loopback:
            try:
                if not ipaddress.ip_address(self.client_address[0]).is_loopback:
                    return False
            except ValueError:
                return False
        elif data_request and self.headers.get("Sec-Fetch-Site") != "same-origin":
            return False
        try:
            host = urlparse("//" + self.headers.get("Host", "").lower())
            hostname = host.hostname
            if host.port not in (None, PUBLIC_ADMIN_PORT):
                return False
        except ValueError:
            return False
        if hostname not in ("127.0.0.1", "localhost", "::1"):
            return False
        origin = self.headers.get("Origin")
        if origin:
            try:
                parsed = urlparse(origin)
                if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
                    return False
                if parsed.port not in (None, PUBLIC_ADMIN_PORT):
                    return False
            except ValueError:
                return False
        return True

    def _admin_page(self):
        page = Path(__file__).with_name("settings.html")
        try:
            body = page.read_bytes()
        except OSError:
            return self._json(500, {"error": "Settings page is missing"})
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _admin_settings(self):
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Settings are available only from this computer"})
        pairing_host = _pairing_host()
        pairing_url = f"http://{_url_host(pairing_host)}:{PUBLIC_API_PORT}" if pairing_host else None
        data = {"root": ROOT, "token": TOKEN, "docling": DOCLING_CONFIG,
                "apiPort": PUBLIC_API_PORT, "adminPort": PUBLIC_ADMIN_PORT,
                "pairingUrl": pairing_url, "hostNeedsManual": pairing_host is None}
        return self._json(200, data)

    def _admin_status(self):
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Settings are available only from this computer"})
        api_server = getattr(self.server, "api_server", None)
        api_thread = getattr(self.server, "api_thread", None)
        listener_running = bool(api_server and api_thread and api_thread.is_alive())
        lan_host = _pairing_host()
        exists = os.path.isdir(ROOT)
        writable = exists and os.access(ROOT, os.W_OK)
        with DIAGNOSTIC_LOCK:
            last_activity = LAST_ACTIVITY
        configured_docling = bool(str(DOCLING_CONFIG.get("docling_url") or "").strip())
        return self._json(200, {
            "ok": True,
            "uptimeSeconds": max(0, int(time.time() - STARTED_AT)),
            "apiListener": {"running": listener_running,
                             "address": f"{_url_host(lan_host)}:{PUBLIC_API_PORT}" if lan_host else None,
                             "port": PUBLIC_API_PORT},
            "backup": {"root": ROOT, "exists": exists, "writable": writable,
                       "ready": exists and writable, "recordings": _library_count()},
            "authenticated": bool(TOKEN),
            "docling": {"enabled": bool(DOCLING_CONFIG.get("enabled")),
                        "configured": configured_docling,
                        "usingDefault": not configured_docling},
            "lastActivity": last_activity,
        })

    def _admin_logs(self):
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Settings are available only from this computer"})
        with DIAGNOSTIC_LOCK:
            events = list(DIAGNOSTIC_LOG)
        return self._json(200, {"events": events})

    def _admin_docling_check(self):
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Settings are available only from this computer"})
        # Copy under lock so a concurrent save cannot mutate the probe configuration.
        with CONFIG_LOCK:
            config = dict(DOCLING_CONFIG)
        result = _docling_probe(config)
        result.update({"enabled": bool(config.get("enabled")),
                       "configured": bool(str(config.get("docling_url") or "").strip()),
                       "usingDefault": not bool(str(config.get("docling_url") or "").strip())})
        return self._json(200, result)

    def _admin_save(self):
        global ROOT, TOKEN, DOCLING_CONFIG, CONFIG
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Settings are available only from this computer"})
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
            return self._json(415, {"error": "Use JSON"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 16384:
                raise ValueError("Invalid settings size")
            supplied = json.loads(self.rfile.read(length))
            if not isinstance(supplied, dict):
                raise ValueError("Settings must be a JSON object")
            root_value = supplied.get("root")
            if not isinstance(root_value, str) or not root_value.strip():
                raise ValueError("Choose a backup folder path")
            root = str(Path(root_value.strip()).expanduser().resolve())
            token = str(supplied.get("token", "")).strip()
            docling = supplied.get("docling", {})
            if not root or len(root) > 4096 or not token or len(token) > 256:
                raise ValueError("Choose a folder and provide a token")
            if not isinstance(docling, dict):
                raise ValueError("Invalid Docling settings")
            os.makedirs(root, exist_ok=True)
            if not os.path.isdir(root) or not os.access(root, os.W_OK):
                raise ValueError("The selected folder is not writable")
            docling_url = str(docling.get("docling_url", "")).strip()
            if len(docling_url) > 2048 or (docling_url and not docling_url.startswith(("http://", "https://"))):
                raise ValueError("Docling URL must start with http:// or https://")
            with CONFIG_LOCK:
                docling_config = dict(DOCLING_CONFIG)
                docling_config.update({"enabled": bool(docling.get("enabled")),
                                       "docling_url": docling_url})
                config = {"root": root, "token": token, "docling": docling_config}
                if CONFIG_PATH:
                    _write_config(CONFIG_PATH, config)
                ROOT, TOKEN, DOCLING_CONFIG, CONFIG = root, token, docling_config, config
            return self._json(200, {"ok": True})
        except (ValueError, OSError, json.JSONDecodeError) as exc:
            return self._json(400, {"error": str(exc)})

    def _json(self, code, obj):
        self._response_code = code
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def send_response(self, code, message=None):
        self._response_code = code
        super().send_response(code, message)

    def end_headers(self):
        super().end_headers()
        self._maybe_log_response()

    def _maybe_log_response(self):
        path = urlparse(getattr(self, "path", "")).path
        status = getattr(self, "_response_code", 0)
        if path in ("/health", "/index.json") and status < 400:
            return
        if path in ("/admin/", "/admin/api/status", "/admin/api/logs"):
            return
        if path == "/admin/api/settings" and getattr(self, "command", "") == "GET":
            return
        endpoint = _log_endpoint(path)
        method = _log_method(getattr(self, "command", ""))
        if status < 400 and (method == "GET" and endpoint == "/other"):
            return
        level = "ERROR" if status >= 500 else "WARNING" if status >= 400 else "INFO"
        if status >= 500:
            message = "Request failed on Link"
        elif status >= 400:
            message = "Request was rejected or could not be completed"
        elif endpoint in ("/docling/pre", "/docling/post"):
            message = "Docling request completed"
        elif endpoint == "/admin/api/docling-check":
            message = "Docling health check completed"
        elif method in ("POST", "PUT"):
            message = "Library or settings change completed"
        else:
            message = "Library request completed"
        _record_log(level, method, endpoint, status, message)

    def log_message(self, fmt, *args):
        # Keep terminal output private too: paths may contain recording titles or other data.
        path = urlparse(getattr(self, "path", "")).path
        if path in ("/admin/", "/admin/api/status", "/admin/api/logs"):
            return
        if path == "/admin/api/settings" and self.command == "GET":
            return
        if path in ("/health", "/index.json") and getattr(self, "_response_code", 0) < 400:
            return
        endpoint = _log_endpoint(path)
        sys.stderr.write(f"[link] {_log_method(getattr(self, 'command', ''))} {endpoint}\n")

def main():
    global TOKEN, ROOT, CONFIG_PATH, CONFIG, DOCLING_CONFIG, ADMIN_PORT
    global PUBLIC_API_PORT, PUBLIC_ADMIN_PORT, PUBLIC_HOST_REQUIRED
    ap = argparse.ArgumentParser()
    ap.add_argument("--root")
    ap.add_argument("--port", type=int, default=8732)
    ap.add_argument("--admin-port", type=int, default=8733)
    ap.add_argument("--admin-host", default="127.0.0.1")
    ap.add_argument("--token", default=None)
    ap.add_argument("--token-file", type=Path)
    ap.add_argument("--config", type=Path)
    a = ap.parse_args()
    CONFIG_PATH = (a.config or _default_config_path()).expanduser().resolve()
    CONFIG = _read_config(CONFIG_PATH)
    ADMIN_PORT = a.admin_port
    PUBLIC_API_PORT = _env_port("LINK_PUBLIC_API_PORT", a.port)
    PUBLIC_ADMIN_PORT = _env_port("LINK_PUBLIC_ADMIN_PORT", a.admin_port)
    PUBLIC_HOST_REQUIRED = a.admin_host not in ("127.0.0.1", "::1", "localhost")
    if a.token_file:
        a.token = a.token_file.read_text(encoding="utf-8").strip()
        if not a.token:
            ap.error("--token-file must contain a non-empty token")
    saved_root = CONFIG.get("root")
    if not isinstance(saved_root, str) or not saved_root.strip():
        saved_root = ""
    default_root = os.environ.get("LINK_DEFAULT_LIBRARY_ROOT", "").strip()
    ROOT = os.path.expanduser(a.root or saved_root or default_root or "~/Secure Pocket Library")
    os.makedirs(ROOT, exist_ok=True)
    TOKEN = a.token or CONFIG.get("token") or secrets.token_urlsafe(24)
    saved_docling = CONFIG.get("docling")
    DOCLING_CONFIG = dict(saved_docling) if isinstance(saved_docling, dict) else {}
    if "docling_url" not in DOCLING_CONFIG and "LINK_DEFAULT_DOCLING_URL" in os.environ:
        DOCLING_CONFIG["docling_url"] = os.environ["LINK_DEFAULT_DOCLING_URL"].strip()
    if "enabled" not in DOCLING_CONFIG:
        DOCLING_CONFIG["enabled"] = _env_bool("LINK_DEFAULT_DOCLING_ENABLED", False)
    CONFIG = {"root": os.path.realpath(ROOT), "token": TOKEN, "docling": DOCLING_CONFIG}
    if not a.token and not a.token_file:
        _write_config(CONFIG_PATH, CONFIG)
    api = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    admin = ThreadingHTTPServer((a.admin_host, a.admin_port), Handler)
    api.api_port = a.port
    admin.api_port = a.port
    admin.is_admin = True
    admin.api_server = api
    print(f"Secure Pocket Link library: {ROOT}")
    print(f"Android API: http://0.0.0.0:{a.port} (token protected)")
    print(f"Settings: http://127.0.0.1:{a.admin_port}/admin/")
    api_thread = threading.Thread(target=api.serve_forever, name="pocket-link-api", daemon=True)
    admin.api_thread = api_thread
    api_thread.start()
    admin.serve_forever()


def _default_config_path():
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "secure-pocket-link" / "config.json"


def _env_port(name, default):
    value = os.environ.get(name)
    if not value:
        return default
    try:
        port = int(value)
    except ValueError as exc:
        raise SystemExit(f"{name} must be a valid TCP port") from exc
    if not 1 <= port <= 65535:
        raise SystemExit(f"{name} must be between 1 and 65535")
    return port


def _env_bool(name, default):
    value = os.environ.get(name)
    if value is None:
        return default
    lowered = value.strip().lower()
    if lowered in ("1", "true", "yes", "on"):
        return True
    if lowered in ("0", "false", "no", "off"):
        return False
    raise SystemExit(f"{name} must be true or false")


def _pairing_host():
    supplied = os.environ.get("LINK_PUBLIC_HOST", "").strip()
    if supplied:
        parsed = urlparse("//" + supplied)
        if not parsed.hostname or parsed.port is not None or parsed.path:
            return None
        return parsed.hostname
    if PUBLIC_HOST_REQUIRED:
        return None
    # UDP connect selects the route's local address without sending a packet.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            address = probe.getsockname()[0]
            if not address.startswith("127."):
                return address
    except OSError:
        pass
    return None


def _url_host(host):
    return f"[{host}]" if ":" in host and not host.startswith("[") else host


def _read_config(path):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Cannot read settings file {path}: {exc}")


def _write_config(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(data, indent=2) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.chmod(temp, 0o600)
        except OSError:
            pass
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)

if __name__ == "__main__":
    main()
