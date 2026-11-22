#!/usr/bin/env python3
"""Secure Pocket Link — desktop library server and localhost settings UI.

Serves recording bundles to authenticated Android clients. The phone API
binds loopback by default. Use direct TLS or a trusted local HTTPS proxy
to connect a phone. The settings listener uses a separate admin code.
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
import subprocess
import sys
import time
import hmac
import re
import shutil
import ssl
import unicodedata
import tempfile
import threading
import zipfile
from contextlib import contextmanager
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote
from urllib.request import Request
from urllib.error import HTTPError, URLError
from link_crypto import (MAGIC as ENCRYPTED_MAGIC, decrypt_to_temporary,
                         encrypt_file_atomic, encrypt_stream,
                         is_encrypted, load_or_create_key, migrate_key_file,
                         seal_json, unseal_json)

ROOT = os.path.expanduser("~/secure-pocket-link/inbox")
TOKEN = None
ADMIN_CODE = ""
READABLE_LIBRARY = False
CONFIG = {}
CONFIG_PATH = None
ADMIN_PORT = 8733
PUBLIC_API_PORT = 8732
PUBLIC_ADMIN_PORT = 8733
PUBLIC_HOST_REQUIRED = False
PUBLIC_URL = ""
API_TLS = False
API_BIND = "127.0.0.1"
ENCRYPTION_KEY = None
ENCRYPTION_KEY_PATH = None
DOCLING_CONFIG = {}
INTERNAL_DOCLING_API_KEY = ""
DOCLING_SLOT = threading.BoundedSemaphore(1)
CONFIG_LOCK = threading.RLock()
LIBRARY_WRITE_LOCK = threading.RLock()
UPLOAD_SLOT = threading.BoundedSemaphore(1)
ARTIFACT_READ_SLOTS = threading.BoundedSemaphore(2)
MAX_UPLOAD_BYTES = 128 * 1024**2
MAX_DOC_INPUT_BYTES = 128 * 1024**2
BUNDLE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}-[a-f0-9]{8}\.zip")
RECORDING_ID = re.compile(BUNDLE.pattern.removesuffix(r"\.zip"))
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
            count += sum(1 for entry in entries if entry.is_file() and entry.name.lower().endswith(".mp3")
                         and not _replaced_loose_alias(entry.name))
    except OSError:
        return 0
    library = Path(ROOT) / "library"
    try:
        with os.scandir(library) as entries:
            count += sum(1 for entry in entries
                         if (entry.is_dir(follow_symlinks=False) and RECORDING_ID.fullmatch(entry.name))
                         or (entry.is_file(follow_symlinks=False) and BUNDLE.fullmatch(entry.name)
                             and not (library / Path(entry.name).stem).is_dir()))
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
    base = str(config.get("docling_url") or "https://docling:5001").strip().rstrip("/")
    if not base.startswith(("http://", "https://")):
        return {"state": "unreachable", "checkedAt": _timestamp(), "detail": "Invalid service address"}
    try:
        from docling_client import _base_url, open_docling_request
        base = _base_url(config)
        headers = {"Accept": "application/json"}
        api_key = str(config.get("docling_api_key") or "").strip()
        if not api_key and _is_internal_docling(config):
            api_key = INTERNAL_DOCLING_API_KEY
        if api_key:
            headers["X-Api-Key"] = api_key
        request = Request(base + "/health", headers=headers, method="GET")
        with open_docling_request(request, timeout=2.5) as response:
            return {"state": "reachable" if 200 <= response.status < 300 else "unreachable",
                    "httpStatus": response.status, "checkedAt": _timestamp()}
    except HTTPError as exc:
        return {"state": "degraded", "httpStatus": int(exc.code), "checkedAt": _timestamp(),
                "detail": "Service replied, but its health check did not succeed"}
    except Exception:
        return {"state": "unreachable", "checkedAt": _timestamp(), "detail": "No response within 2.5 seconds"}


def stream_sha(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(65536), b""):
        digest.update(chunk)
    return digest.hexdigest()


def _is_internal_docling(config):
    try:
        parsed = urlparse(str(config.get("docling_url") or "https://docling:5001").strip())
        return parsed.scheme == "https" and parsed.hostname == "docling" and parsed.port in (None, 5001)
    except ValueError:
        return False


def _replaced_loose_alias(name):
    return (name.startswith(".link-") and (Path(ROOT) / "library" / name[6:-4]).is_dir())


def _recording_member(name):
    # The Android writer already normalizes names to letters, numbers and basic punctuation.
    return (0 < len(name) <= 110 and not name.startswith(".")
            and all(ch.isalnum() or ch in "_. -" for ch in name)
            and (name.endswith((".mp3", ".transcript.md", ".summary.md")) or name == "title.txt"))


def _recording_files(folder):
    if folder.is_symlink():
        raise OSError("Invalid recording folder")
    parts = sorted(path for path in folder.iterdir() if _recording_member(path.name))
    if (not 1 <= len(parts) <= 4 or any(path.is_symlink() or not path.is_file() for path in parts)
            or sum(path.suffix == ".mp3" for path in parts) != 1):
        raise OSError("Invalid recording folder")
    sizes = [part.stat().st_size for part in parts]
    if sum(sizes) > MAX_UPLOAD_BYTES:
        raise OSError("Recording is too large")
    return parts


@contextmanager
def _open_bundle(path):
    folder = path.with_suffix("")
    with LIBRARY_WRITE_LOCK:
        stream = tempfile.TemporaryFile() if folder.is_dir() else None
        if stream is not None:
            try:
                with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as archive:
                    for part in _recording_files(folder):
                        archive.write(part, part.name)
                stream.seek(0)
            except BaseException:
                stream.close()
                raise
    if stream is not None:
        with stream:
            yield stream
        return
    with _open_artifact(path) as stream:
        yield stream


def _store_bundle(source, destination):
    folder = destination.with_suffix("")
    if not READABLE_LIBRARY and not folder.is_dir():
        return _replace_with_encrypted(source, destination)
    if folder.is_symlink():
        raise OSError("Invalid recording folder")
    backup = folder.with_name("." + folder.name + ".previous")
    if backup.exists():
        if folder.exists():
            shutil.rmtree(backup)
        else:
            os.rename(backup, folder)
    stage = Path(tempfile.mkdtemp(prefix=".recording-", dir=destination.parent))
    try:
        with zipfile.ZipFile(source) as archive:
            for entry in archive.infolist():
                if not _recording_member(entry.filename):
                    raise ValueError("Invalid recording file name")
                part = stage / entry.filename
                with archive.open(entry) as incoming, part.open("xb") as stored:
                    shutil.copyfileobj(incoming, stored)
                    stored.flush()
                    os.fsync(stored.fileno())
        _recording_files(stage)
        if folder.exists():
            os.rename(folder, backup)
        try:
            os.rename(stage, folder)
        except OSError:
            if backup.exists():
                os.rename(backup, folder)
            raise
        if backup.exists():
            shutil.rmtree(backup)
        destination.unlink(missing_ok=True)
    finally:
        if stage.exists():
            shutil.rmtree(stage)


@contextmanager
def _open_artifact(path):
    """Yield verified plaintext for legacy or encrypted library artifacts."""
    path = Path(path)
    if not is_encrypted(path):
        with path.open("rb") as stream:
            yield stream
        return
    if ENCRYPTION_KEY is None:
        raise OSError("Link encryption key is unavailable")
    try:
        ARTIFACT_READ_SLOTS.acquire()
        with path.open("rb") as source:
            stream = decrypt_to_temporary(source, ENCRYPTION_KEY, path.name)
    except Exception:
        try:
            ARTIFACT_READ_SLOTS.release()
        except ValueError:
            pass
        raise OSError("Encrypted artifact failed authentication") from None
    try:
        yield stream
    finally:
        stream.close()
        ARTIFACT_READ_SLOTS.release()


def _encrypted_temp(source_path, destination):
    destination = Path(destination)
    directory = destination.parent
    fd, name = tempfile.mkstemp(prefix="encrypted-", suffix=".tmp", dir=directory)
    try:
        with Path(source_path).open("rb") as source, os.fdopen(fd, "wb") as target:
            encrypt_stream(source, target, ENCRYPTION_KEY, destination.name)
            target.flush()
            os.fsync(target.fileno())
        return Path(name)
    except BaseException:
        Path(name).unlink(missing_ok=True)
        raise


def _replace_with_encrypted(source_path, destination):
    encrypted = _encrypted_temp(source_path, destination)
    try:
        os.replace(encrypted, destination)
        try:
            fd = os.open(Path(destination).parent, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        except OSError:
            pass
    finally:
        encrypted.unlink(missing_ok=True)


def _migrate_artifacts(root):
    """Encrypt legacy bundles and loose recording/sidecar files in place."""
    root = Path(root)
    candidates = []
    library = root / "library"
    if library.is_dir():
        for backup in library.glob(".*.previous"):
            name = backup.name[1:-len(".previous")]
            folder = library / name
            if RECORDING_ID.fullmatch(name) and backup.is_dir() and not backup.is_symlink():
                if folder.is_dir():
                    shutil.rmtree(backup)
                elif not folder.exists():
                    os.rename(backup, folder)
    if READABLE_LIBRARY:
        return
    if library.is_dir():
        candidates.extend(path for path in library.iterdir()
                          if path.is_file() and path.suffix.lower() == ".zip")
    if root.is_dir():
        candidates.extend(path for path in root.iterdir()
                          if path.is_file() and path.suffix.lower() in {".mp3", ".json", ".md", ".txt"})
    for path in candidates:
        if path.is_symlink() or is_encrypted(path):
            continue
        encrypt_file_atomic(path, ENCRYPTION_KEY)


def _decode_saved_config(data, key):
    """Read encrypted settings while accepting pre-encryption config files."""
    data = dict(data) if isinstance(data, dict) else {}
    sealed = data.pop("encrypted_settings", None)
    if sealed:
        secrets_data = unseal_json(sealed, key)
        if not isinstance(secrets_data, dict):
            raise ValueError("Encrypted settings are not a JSON object")
        data.update(secrets_data)
    return data


def _encode_saved_config(data, key):
    public = {k: v for k, v in data.items() if k not in {"token", "docling"}}
    public["encrypted_settings"] = seal_json(
        {"token": data.get("token", ""), "docling": data.get("docling", {})}, key)
    return public

def scan():
    out = []
    if not os.path.isdir(ROOT):
        return out
    for name in sorted(os.listdir(ROOT)):
        if not name.lower().endswith(".mp3") or _replaced_loose_alias(name):
            continue
        path = os.path.join(ROOT, name)
        entry = {
            "name": name,
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
        try:
            entry["mtime"] = int(os.path.getmtime(path))
            with _open_artifact(path) as f:
                f.seek(0, os.SEEK_END)
                entry["bytes"] = f.tell()
                f.seek(0)
                while True:
                    chunk = f.read(1 << 20)
                    if not chunk:
                        break
                    h.update(chunk)
            entry["sha16"] = h.hexdigest()[:16]
        except (OSError, ValueError):
            continue
        out.append(entry)
    bundles = Path(ROOT) / "library"
    if bundles.is_dir():
        for folder in sorted(bundles.iterdir()):
            if not RECORDING_ID.fullmatch(folder.name) or not folder.is_dir():
                continue
            try:
                with LIBRARY_WRITE_LOCK:
                    audio = next(path for path in _recording_files(folder) if path.suffix == ".mp3")
                    with audio.open("rb") as stream:
                        sha = stream_sha(stream)
                    out.append({"name": audio.name, "bundle": folder.name + ".zip",
                                "bytes": audio.stat().st_size, "mtime": int(audio.stat().st_mtime),
                                "sha16": sha[:16], "sidecar": None})
            except OSError:
                continue
        for path in sorted(bundles.glob("*.zip")):
            if not BUNDLE.fullmatch(path.name) or path.with_suffix("").is_dir():
                continue
            try:
                with _open_artifact(path) as artifact, zipfile.ZipFile(artifact) as z:
                    audio = next(i for i in z.infolist() if i.filename.endswith(".mp3"))
                    with z.open(audio) as f:
                        sha = stream_sha(f)
                    out.append({"name": audio.filename, "bundle": path.name, "bytes": audio.file_size,
                                "mtime": int(path.stat().st_mtime), "sha16": sha[:16], "sidecar": None})
            except (OSError, StopIteration, zipfile.BadZipFile):
                continue
    return out

class Handler(BaseHTTPRequestHandler):
    timeout = 30
    server_version = "SecurePocketLink"
    sys_version = ""

    def _check(self, qs):
        supplied = self.headers.get("X-Pocket-Token", "")
        if TOKEN and not hmac.compare_digest(supplied.encode("utf-8", "replace"), TOKEN.encode()):
            time.sleep(0.2)
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
        if self.headers.get("Transfer-Encoding"):
            self._json(400, {"error": "Chunked uploads are not supported"})
            return False
        try:
            raw_size = self.headers.get("Content-Length")
            if raw_size is None or not re.fullmatch(r"[0-9]{1,12}", raw_size.strip()):
                raise ValueError
            size = int(raw_size)
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
        if not UPLOAD_SLOT.acquire(blocking=False):
            return self._json(429, {"error": "Another library upload is already in progress"})
        directory = Path(ROOT) / "library"
        temp = None
        try:
            directory.mkdir(exist_ok=True)
            fd, temp = tempfile.mkstemp(prefix="pocket-upload-", suffix=".zip")
            os.close(fd)
            if not self._copy_body(Path(temp), MAX_UPLOAD_BYTES):
                return
            with zipfile.ZipFile(temp) as bundle:
                content = bundle.infolist()
                filenames = [unicodedata.normalize("NFC", i.filename).casefold() for i in content]
                valid_names = all(not i.is_dir() and _recording_member(i.filename) and
                                  (i.filename.endswith(".mp3") or i.file_size <= 2 * 1024**2) and
                                  i.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                                  for i in content)
                if (not valid_names or not 1 <= len(content) <= 4
                    or len(set(filenames)) != len(filenames)
                    or sum(i.filename.endswith(".mp3") for i in content) != 1
                    or max(i.file_size for i in content if i.filename.endswith(".mp3")) <= 0
                    or sum(i.file_size for i in content) > MAX_UPLOAD_BYTES):
                    return self._json(400, {"error": "Invalid library bundle"})
                incoming_audio = next(i for i in content if i.filename.endswith(".mp3"))
                with bundle.open(incoming_audio) as stream:
                    incoming_sha = stream_sha(stream)
            destination = directory / name
            with LIBRARY_WRITE_LOCK:
                already_present = destination.is_file() or destination.with_suffix("").is_dir()
                if already_present:
                    try:
                        with _open_bundle(destination) as archive, zipfile.ZipFile(archive) as current:
                            existing = current.infolist()
                            existing_audio = next(i for i in existing if i.filename.endswith(".mp3"))
                            with current.open(existing_audio) as stream:
                                existing_sha = stream_sha(stream)
                        if not hmac.compare_digest(existing_sha, incoming_sha):
                            return self._json(409, {"error": "A different recording already uses this bundle name"})
                    except (OSError, zipfile.BadZipFile, StopIteration):
                        return self._json(409, {"error": "Existing library bundle is invalid"})
                _store_bundle(temp, destination)
                Path(temp).unlink(missing_ok=True)
            self._json(200 if already_present else 201, {"ok": True, "alreadyPresent": already_present})
        except (OSError, ValueError, zipfile.BadZipFile):
            self._json(400, {"error": "Incomplete library bundle"})
        finally:
            if temp:
                Path(temp).unlink(missing_ok=True)
            UPLOAD_SLOT.release()

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
        if not DOCLING_SLOT.acquire(blocking=False):
            return self._json(429, {"error": "Another Docling request is already in progress"})
        try:
            with tempfile.TemporaryDirectory(prefix="pocket-docling-") as workspace:
                audio = path.endswith("/pre")
                source = Path(workspace) / ("recording.mp3" if audio else "summary.md")
                if not self._copy_body(source, MAX_DOC_INPUT_BYTES if audio else 2 * 1024**2):
                    return
                try:
                    from docling_client import process_docling
                    processing_config = dict(DOCLING_CONFIG)
                    if _is_internal_docling(processing_config):
                        processing_config["docling_api_key"] = INTERNAL_DOCLING_API_KEY
                    result = process_docling(source, kind="audio" if audio else "markdown", config=processing_config)
                    payload = {"markdown": result["markdown"]}
                    if result.get("segments") is not None:
                        payload["segments"] = result["segments"]
                    return self._json(200, payload)
                except Exception:
                    sys.stderr.write("[link] Docling processing failed\n")
                    return self._json(502, {"error": "Docling processing failed. Check its settings and model files."})
        finally:
            DOCLING_SLOT.release()

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
        if not UPLOAD_SLOT.acquire(blocking=False):
            return self._json(429, {"error": "Another library upload is already in progress"})
        fd, audio_path = tempfile.mkstemp(prefix="pocket-incoming-", suffix=".mp3")
        os.close(fd)
        bundle_path = None
        try:
            if not self._copy_body(Path(audio_path), MAX_UPLOAD_BYTES):
                return
            with open(audio_path, "rb") as stream:
                actual_sha = stream_sha(stream)
            if not hmac.compare_digest(actual_sha, expected_sha):
                return self._json(422, {"error": "Audio SHA-256 did not match"})
            with LIBRARY_WRITE_LOCK:
                if destination.is_file() or destination.with_suffix("").is_dir():
                    try:
                        with _open_bundle(destination) as artifact, zipfile.ZipFile(artifact) as bundle:
                            audio = next(i for i in bundle.infolist() if i.filename.endswith(".mp3"))
                            with bundle.open(audio) as stream:
                                existing_sha = stream_sha(stream)
                        if not hmac.compare_digest(existing_sha, expected_sha):
                            return self._json(409, {"error": "Recording id already exists with different audio"})
                        return self._json(200, {"ok": True, "bundle": bundle_name,
                                                "sha256": expected_sha, "alreadyPresent": True})
                    except (OSError, zipfile.BadZipFile, StopIteration):
                        return self._json(409, {"error": "Existing recording bundle is invalid"})
                fd, bundle_path = tempfile.mkstemp(prefix="pocket-bundle-", suffix=".zip")
                os.close(fd)
                recording_name = f"{day}_{start}.mp3"
                with zipfile.ZipFile(bundle_path, "w", compression=zipfile.ZIP_STORED) as bundle:
                    bundle.write(audio_path, recording_name)
                    bundle.writestr("title.txt", f"Recording {day} {start[0:2]}:{start[2:4]}")
                _store_bundle(bundle_path, destination)
                return self._json(201, {"ok": True, "bundle": bundle_name,
                                        "sha256": expected_sha, "alreadyPresent": False})
        except (OSError, ValueError, zipfile.BadZipFile):
            return self._json(500, {"error": "Could not store recording"})
        finally:
            Path(audio_path).unlink(missing_ok=True)
            if bundle_path:
                Path(bundle_path).unlink(missing_ok=True)
            UPLOAD_SLOT.release()

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
            if u.path == "/admin/api/encryption-key":
                return self._admin_encryption_key()
            if u.path == "/admin/api/status":
                return self._admin_status()
            if u.path == "/admin/api/logs":
                return self._admin_logs()
            return self._json(404, {"error": "Unknown settings path"})
        qs = parse_qs(u.query)
        if not self._check(qs):
            return
        if u.path == "/health":
            return self._json(200, {"ok": True, "server": "secure-pocket-link",
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
            if not path.is_file() and not path.with_suffix("").is_dir():
                return self._json(404, {"error": "Missing library bundle"})
            try:
                with _open_bundle(path) as f:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/zip")
                    f.seek(0, os.SEEK_END)
                    size = f.tell()
                    f.seek(0)
                    self.send_header("Content-Length", str(size))
                    self.end_headers()
                    shutil.copyfileobj(f, self.wfile)
            except OSError:
                if not getattr(self, "_response_code", 0):
                    return self._json(500, {"error": "Could not read the library bundle"})
            return
        if u.path.startswith("/file/"):
            name = unquote(u.path[len("/file/"):])
            if "\0" in name:
                return self._json(404, {"error": "not found"})
            path = os.path.realpath(os.path.join(ROOT, name))
            if not path.startswith(os.path.realpath(ROOT) + os.sep) or not os.path.isfile(path):
                return self._json(404, {"error": "not found"})
            ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            with _open_artifact(path) as f:
                f.seek(0, os.SEEK_END)
                size = f.tell()
                f.seek(0)
                self.send_header("Content-Length", str(size))
                self.end_headers()
                while True:
                    chunk = f.read(1 << 20)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return
        self._json(404, {"error": "unknown path"})

    def _admin_request_allowed(self, data_request=False):
        """Require loopback browser origin and a secret code for every admin data route."""
        import ipaddress
        bound_loopback = self.server.server_address[0] in ("127.0.0.1", "::1", "localhost")
        if bound_loopback:
            try:
                if not ipaddress.ip_address(self.client_address[0]).is_loopback:
                    return False
            except ValueError:
                return False
        if data_request:
            supplied = self.headers.get("X-Admin-Token", "").encode("utf-8", "replace")
            if not ADMIN_CODE or not hmac.compare_digest(supplied, ADMIN_CODE.encode()):
                return False
            if self.headers.get("Sec-Fetch-Site") not in (None, "same-origin"):
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
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                         "style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(body)

    def _admin_settings(self):
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Settings are available only from this computer"})
        pairing_url = _public_pairing_url()
        data = {"root": ROOT, "token": TOKEN, "libraryFiles": READABLE_LIBRARY,
                "docling": {"enabled": bool(DOCLING_CONFIG.get("enabled")),
                            "docling_url": DOCLING_CONFIG.get("docling_url", "")},
                "apiPort": PUBLIC_API_PORT, "adminPort": PUBLIC_ADMIN_PORT,
                "pairingUrl": pairing_url, "pairingScheme": "https" if API_TLS or PUBLIC_URL else "http",
                "tailscaleUrl": _tailscale_serve_url(),
                "encryptionKeyPath": str(ENCRYPTION_KEY_PATH) if ENCRYPTION_KEY_PATH else "",
                "hostNeedsManual": pairing_url is None}
        return self._json(200, data)

    def _admin_encryption_key(self):
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Encryption key download is available only from unlocked Link settings"})
        if not isinstance(ENCRYPTION_KEY, bytes) or len(ENCRYPTION_KEY) != 32:
            return self._json(500, {"error": "Link encryption key is unavailable"})
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition", 'attachment; filename="secure-pocket-link-recovery.key"')
        self.send_header("Content-Length", str(len(ENCRYPTION_KEY)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(ENCRYPTION_KEY)

    def _admin_status(self):
        if not self._admin_request_allowed(data_request=True):
            return self._json(403, {"error": "Settings are available only from this computer"})
        api_server = getattr(self.server, "api_server", None)
        api_thread = getattr(self.server, "api_thread", None)
        listener_running = bool(api_server and api_thread and api_thread.is_alive())
        lan_host = _pairing_host()
        pairing_url = _public_pairing_url()
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
                             "bind": API_BIND, "tls": API_TLS, "pairingUrl": pairing_url,
                             "pairingScheme": "https" if API_TLS or PUBLIC_URL else "http",
                             "tailscaleUrl": _tailscale_serve_url(),
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
        global ROOT, TOKEN, DOCLING_CONFIG, CONFIG, READABLE_LIBRARY
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
            if not root or len(root) > 4096 or not 16 <= len(token) <= 256:
                raise ValueError("Choose a folder and a Link token of at least 16 characters")
            if not isinstance(docling, dict):
                raise ValueError("Invalid Docling settings")
            os.makedirs(root, exist_ok=True)
            if not os.path.isdir(root) or not os.access(root, os.W_OK):
                raise ValueError("The selected folder is not writable")
            docling_url = str(docling.get("docling_url", "")).strip()
            if docling_url:
                parsed = urlparse(docling_url)
                if (len(docling_url) > 2048 or parsed.scheme not in ("http", "https")
                        or not parsed.hostname or parsed.username is not None
                        or parsed.query or parsed.fragment):
                    raise ValueError("Enter a Docling URL without credentials, query, or fragment")
                if parsed.scheme == "http" and parsed.hostname.lower() not in {"localhost", "127.0.0.1", "::1"}:
                    raise ValueError("Docling must use HTTPS unless it runs on this computer")
            with CONFIG_LOCK:
                docling_config = dict(DOCLING_CONFIG)
                docling_config.update({"enabled": bool(docling.get("enabled")),
                                       "docling_url": docling_url})
                readable = supplied.get("libraryFiles", READABLE_LIBRARY) is True
                config = {"root": root, "token": token, "docling": docling_config,
                          "library_files": readable, "public_url": PUBLIC_URL}
                if CONFIG_PATH:
                    _write_config(CONFIG_PATH, config)
                READABLE_LIBRARY = readable
                ROOT, TOKEN, DOCLING_CONFIG, CONFIG = root, token, docling_config, config
            return self._json(200, {"ok": True})
        except OSError:
            return self._json(400, {"error": "Could not access the configured folder or save settings"})
        except ValueError as exc:
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
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        if API_TLS and not getattr(self.server, "is_admin", False):
            self.send_header("Strict-Transport-Security", "max-age=31536000")
        super().end_headers()
        self._maybe_log_response()

    def _maybe_log_response(self):
        path = urlparse(getattr(self, "path", "")).path
        status = getattr(self, "_response_code", 0)
        if path in ("/health", "/index.json") and status < 400:
            return
        if path in ("/admin/", "/admin/api/status", "/admin/api/logs", "/admin/api/encryption-key"):
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
        if path in ("/admin/", "/admin/api/status", "/admin/api/logs", "/admin/api/encryption-key"):
            return
        if path == "/admin/api/settings" and self.command == "GET":
            return
        if path in ("/health", "/index.json") and getattr(self, "_response_code", 0) < 400:
            return
        endpoint = _log_endpoint(path)
        sys.stderr.write(f"[link] {_log_method(getattr(self, 'command', ''))} {endpoint}\n")

class LimitedHTTPServer(ThreadingHTTPServer):
    def __init__(self, address, handler):
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            return self.shutdown_request(request)
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def main():
    global TOKEN, ROOT, CONFIG_PATH, CONFIG, DOCLING_CONFIG, ADMIN_PORT, ENCRYPTION_KEY
    global PUBLIC_API_PORT, PUBLIC_ADMIN_PORT, PUBLIC_HOST_REQUIRED, API_TLS, API_BIND
    global ADMIN_CODE, READABLE_LIBRARY, PUBLIC_URL, ENCRYPTION_KEY_PATH
    global INTERNAL_DOCLING_API_KEY
    ap = argparse.ArgumentParser()
    ap.add_argument("--root")
    ap.add_argument("--port", type=int, default=8732)
    ap.add_argument("--api-host", default="127.0.0.1")
    ap.add_argument("--proxy-backend", action="store_true",
                    help="Bind the API to all interfaces for a trusted local TLS proxy")
    ap.add_argument("--tls-cert", type=Path)
    ap.add_argument("--tls-key", type=Path)
    ap.add_argument("--admin-port", type=int, default=8733)
    ap.add_argument("--admin-host", default="127.0.0.1")
    ap.add_argument("--token", default=None)
    ap.add_argument("--token-file", type=Path)
    ap.add_argument("--config", type=Path)
    ap.add_argument("--key-file", type=Path)
    ap.add_argument("--library-files", action="store_true",
                    help="Store readable recording folders on a volume you encrypt")
    a = ap.parse_args()
    api_key_file = os.environ.get("LINK_DOCLING_API_KEY_FILE", "").strip()
    if api_key_file:
        try:
            INTERNAL_DOCLING_API_KEY = Path(api_key_file).read_text(encoding="utf-8").strip()
        except OSError as exc:
            ap.error(f"Cannot read the internal Docling API key file: {exc}")
        if len(INTERNAL_DOCLING_API_KEY) < 32:
            ap.error("The internal Docling API key file is invalid")
    CONFIG_PATH = (a.config or _default_config_path()).expanduser().resolve()
    key_spec = (a.key_file or _default_key_path()).expanduser()
    key_path = key_spec.parent.resolve() / key_spec.name
    ENCRYPTION_KEY_PATH = key_path
    legacy_key_path = CONFIG_PATH.with_name("link.key")
    try:
        if key_path != legacy_key_path and not key_path.exists() and legacy_key_path.exists():
            migrate_key_file(legacy_key_path, key_path)
    except (OSError, ValueError) as exc:
        ap.error(f"Cannot migrate the legacy Link encryption key: {exc}")
    try:
        raw_config = json.loads(CONFIG_PATH.read_text(encoding="utf-8")) if CONFIG_PATH.is_file() else {}
    except (OSError, json.JSONDecodeError):
        raw_config = {}
    candidate_root = os.path.expanduser(a.root or (raw_config.get("root", "") if isinstance(raw_config, dict) else "")
                                        or os.environ.get("LINK_DEFAULT_LIBRARY_ROOT", "")
                                        or "~/Secure Pocket Library")
    encrypted_artifacts_exist = _has_encrypted_artifacts(candidate_root)
    if not key_path.exists() and ((isinstance(raw_config, dict) and raw_config.get("encrypted_settings"))
                                  or encrypted_artifacts_exist):
        ap.error(f"Link encryption key is missing at {key_path}; restore it from backup before starting")
    try:
        ENCRYPTION_KEY = load_or_create_key(key_path)
    except (OSError, ValueError) as exc:
        ap.error(f"Cannot load Link encryption key: {exc}")
    CONFIG = _read_config(CONFIG_PATH)
    READABLE_LIBRARY = a.library_files or CONFIG.get("library_files") is True
    PUBLIC_URL = os.environ.get("LINK_PUBLIC_URL", str(CONFIG.get("public_url") or "")).strip()
    ADMIN_PORT = a.admin_port
    PUBLIC_API_PORT = _env_port("LINK_PUBLIC_API_PORT", a.port)
    PUBLIC_ADMIN_PORT = _env_port("LINK_PUBLIC_ADMIN_PORT", a.admin_port)
    PUBLIC_HOST_REQUIRED = a.admin_host not in ("127.0.0.1", "::1", "localhost")
    if bool(a.tls_cert) != bool(a.tls_key):
        ap.error("--tls-cert and --tls-key must be provided together")
    if a.proxy_backend and (a.tls_cert or a.api_host != "127.0.0.1"):
        ap.error("--proxy-backend cannot be combined with --api-host or direct TLS flags")
    if a.proxy_backend:
        API_BIND = "0.0.0.0"
    else:
        API_BIND = a.api_host
    API_TLS = bool(a.tls_cert)
    if API_BIND not in ("127.0.0.1", "::1", "localhost") and not (API_TLS or a.proxy_backend):
        ap.error("Non-loopback API binding requires direct TLS or --proxy-backend")
    if API_BIND not in ("127.0.0.1", "::1", "localhost"):
        print("WARNING: Link API is bound on all network interfaces. Keep it behind a trusted TLS proxy and restrict network access.", file=sys.stderr)
    if a.proxy_backend and not PUBLIC_URL:
        print("Warning: proxy backend has no LINK_PUBLIC_URL; pairing URL must be entered manually.", file=sys.stderr)
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
    TOKEN = a.token or CONFIG.get("token") or secrets.token_urlsafe(32)
    if not 16 <= len(TOKEN) <= 256:
        ap.error("The Link token must contain 16 to 256 characters")
    saved_docling = CONFIG.get("docling")
    DOCLING_CONFIG = dict(saved_docling) if isinstance(saved_docling, dict) else {}
    default_docling_url = os.environ.get("LINK_DEFAULT_DOCLING_URL", "").strip()
    if default_docling_url:
        saved_docling_url = str(DOCLING_CONFIG.get("docling_url") or "").strip()
        if not saved_docling_url or (default_docling_url == "https://docling:5001"
                                     and saved_docling_url == "http://docling:5001"):
            DOCLING_CONFIG["docling_url"] = default_docling_url
    if "enabled" not in DOCLING_CONFIG:
        DOCLING_CONFIG["enabled"] = _env_bool("LINK_DEFAULT_DOCLING_ENABLED", False)
    _public_pairing_url()
    CONFIG = {"root": os.path.realpath(ROOT), "token": TOKEN, "docling": DOCLING_CONFIG,
              "library_files": READABLE_LIBRARY, "public_url": PUBLIC_URL}
    _migrate_artifacts(ROOT)
    if not a.token and not a.token_file:
        _write_config(CONFIG_PATH, CONFIG)
    ADMIN_CODE = secrets.token_urlsafe(32)
    code_path = Path(os.environ.get("LINK_ADMIN_TOKEN_FILE", str(CONFIG_PATH.with_name("admin.token")))).expanduser()
    code_path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_code = tempfile.mkstemp(prefix=".admin-", dir=code_path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(ADMIN_CODE + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_code, code_path)
    finally:
        Path(temp_code).unlink(missing_ok=True)
    print(f"Settings unlock code file: {code_path}")
    api = LimitedHTTPServer((API_BIND, a.port), Handler)
    if API_TLS:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(a.tls_cert.expanduser()), str(a.tls_key.expanduser()))
        api.socket = context.wrap_socket(api.socket, server_side=True, do_handshake_on_connect=False)
    admin = LimitedHTTPServer((a.admin_host, a.admin_port), Handler)
    api.api_port = a.port
    admin.api_port = a.port
    admin.is_admin = True
    admin.api_server = api
    print(f"Secure Pocket Link library: {ROOT}")
    print(f"Android API: {'https' if API_TLS else 'http'}://{API_BIND}:{a.port} (token protected)")
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


def _default_key_path():
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    return base / "secure-pocket-link-keys" / "link.key"


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


def _tailscale_serve_url():
    """Return a Tailscale Serve origin only when its root proxies to Link's API."""
    executable = shutil.which("tailscale")
    if not executable:
        return None
    try:
        result = subprocess.run([executable, "serve", "status", "--json"],
                                capture_output=True, text=True, timeout=1.5, check=False)
        if result.returncode != 0:
            return None
        status = json.loads(result.stdout)
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
        return None
    web = status.get("Web")
    if not isinstance(web, dict):
        return None
    for authority, config in web.items():
        if not isinstance(config, dict):
            continue
        handlers = config.get("Handlers")
        root = handlers.get("/") if isinstance(handlers, dict) else None
        proxy = urlparse(str(root.get("Proxy") or "")) if isinstance(root, dict) else None
        try:
            if (proxy is None or proxy.scheme != "http"
                    or proxy.hostname not in ("127.0.0.1", "localhost", "::1")
                    or proxy.port != PUBLIC_API_PORT):
                continue
        except ValueError:
            continue
        parsed = urlparse("//" + str(authority))
        try:
            if not parsed.hostname or parsed.username or parsed.password or parsed.path:
                continue
            if parsed.port is not None and not 1 <= parsed.port <= 65535:
                continue
        except ValueError:
            continue
        return f"https://{parsed.netloc}"
    return None


def _public_pairing_url():
    configured = PUBLIC_URL
    if configured:
        parsed = urlparse(configured)
        try:
            valid_port = parsed.port is None or 1 <= parsed.port <= 65535
        except ValueError:
            valid_port = False
        if (parsed.scheme != "https" or not parsed.hostname or not valid_port
                or parsed.username or parsed.password or parsed.path not in ("", "/")
                or parsed.query or parsed.fragment):
            raise ValueError("LINK_PUBLIC_URL must be an HTTPS origin such as https://link.example:8443")
        return f"https://{parsed.netloc}"
    if not API_TLS or API_BIND in ("127.0.0.1", "::1", "localhost"):
        return None
    host = _pairing_host()
    if not host:
        return None
    return f"https://{_url_host(host)}:{PUBLIC_API_PORT}"


def _url_host(host):
    return f"[{host}]" if ":" in host and not host.startswith("[") else host


def _read_config(path):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return _decode_saved_config(data, ENCRYPTION_KEY) if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}
    except Exception:
        raise SystemExit(f"Cannot read or authenticate settings file {path}; check its integrity and Link key") from None


def _has_encrypted_artifacts(root):
    root = Path(root)
    candidates = []
    try:
        library = root / "library"
        if library.is_dir():
            candidates.extend(path for path in library.iterdir() if path.suffix.lower() == ".zip")
        if root.is_dir():
            candidates.extend(path for path in root.iterdir()
                              if path.suffix.lower() in {".mp3", ".json", ".md", ".txt"})
        return any(not path.is_symlink() and is_encrypted(path) for path in candidates)
    except OSError:
        return False


def _write_config(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(_encode_saved_config(data, ENCRYPTION_KEY), indent=2) + "\n")
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
