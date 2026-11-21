"""Small stdlib client for the official Docling Serve conversion API.

Secure Pocket Link owns the public API and calls this adapter for each job. The
Docling container stays on the private Compose network; no Docker socket or
container control is needed.
"""
from __future__ import annotations

import json
import re
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


DEFAULT_DOCLING_URL = "http://docling:5001"
DEFAULT_TIMEOUT_SECONDS = 600
MAX_RESPONSE_BYTES = 32 * 1024 * 1024
_TIMED_SEGMENT = re.compile(
    r"^\s*\[time:\s*(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\]\s*(.*?)\s*$"
)


class DoclingError(RuntimeError):
    """A safe-to-return description of a failed Docling request."""


def _base_url(config: dict) -> str:
    value = str(config.get("docling_url") or DEFAULT_DOCLING_URL).strip().rstrip("/")
    url = urlsplit(value)
    if (url.scheme not in ("http", "https") or not url.hostname
            or url.username is not None or url.query or url.fragment):
        raise DoclingError("Docling URL must be an HTTP or HTTPS address without credentials")
    return value


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def open_docling_request(request, *, timeout):
    return build_opener(NoRedirect()).open(request, timeout=timeout)


def _timed_segments(markdown: str) -> list[dict]:
    segments = []
    for line in markdown.splitlines():
        match = _TIMED_SEGMENT.match(line)
        if match:
            text = match.group(3).strip()
            if text:
                segments.append({
                    "start": float(match.group(1)),
                    "end": float(match.group(2)),
                    "text": text,
                })
    return segments


def _document_segments(document: dict) -> list[dict]:
    """Extract audio TrackSource timing from Docling's structured JSON."""
    content = document.get("json_content")
    if isinstance(content, str):
        try:
            content = json.loads(content)
        except json.JSONDecodeError:
            return []
    if not isinstance(content, dict):
        return []

    segments = []
    for item in content.get("texts", []):
        if not isinstance(item, dict):
            continue
        text = str(item.get("text") or item.get("orig") or "").strip()
        if not text:
            continue
        sources = item.get("source") or item.get("sources") or []
        if isinstance(sources, dict):
            sources = [sources]
        for source in sources:
            if not isinstance(source, dict) or source.get("kind") != "track":
                continue
            start, end = source.get("start_time"), source.get("end_time")
            if isinstance(start, (int, float)) and isinstance(end, (int, float)):
                segments.append({"start": float(start), "end": float(end), "text": text})
    return segments


def _multipart(path: Path, kind: str) -> tuple[bytes, str]:
    boundary = "----SecurePocket" + uuid.uuid4().hex
    filename = "recording.mp3" if kind == "audio" else "summary.md"
    mime = "audio/mpeg" if kind == "audio" else "text/markdown; charset=utf-8"
    with path.open("rb") as stream:
        content = stream.read()
    # These controlled filenames and field values do not contain MIME delimiters.
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="files"; filename="{filename}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode("ascii") + content + f"\r\n--{boundary}--\r\n".encode("ascii")
    return body, boundary


def process_docling(source: Path, *, kind: str, config: dict) -> dict:
    """Convert audio or Markdown and return Link's stable response shape.

    Audio requests ask for both Markdown and JSON. Docling's Markdown export
    includes `[time: start-end]` lines for timestamped ASR segments; those are
    mirrored as `segments` for existing Android clients. If the selected ASR
    backend supplies no timing, the optional key is omitted.
    """
    if kind not in ("audio", "markdown"):
        raise DoclingError("Unsupported Docling input kind")
    source = Path(source)
    if not source.is_file():
        raise DoclingError("Docling input file is missing")

    body, boundary = _multipart(source, kind)
    params = [
        ("from_formats", "audio" if kind == "audio" else "md"),
        ("to_formats", "md"),
    ]
    if kind == "audio":
        params.append(("to_formats", "json"))
    # Starlette/FastAPI parses the regular form fields before the file part.
    form_body = b"".join(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n".encode("ascii")
        for key, value in params
    ) + body

    headers = {
        "Accept": "application/json",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
    }
    api_key = str(config.get("docling_api_key") or "").strip()
    if api_key:
        headers["X-Api-Key"] = api_key
    request = Request(_base_url(config) + "/v1/convert/file", data=form_body, headers=headers, method="POST")
    timeout = config.get("docling_timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
    try:
        with open_docling_request(request, timeout=min(900, max(1, int(timeout)))) as response:
            contents = response.read(MAX_RESPONSE_BYTES + 1)
            if len(contents) > MAX_RESPONSE_BYTES:
                raise DoclingError("Docling response exceeded the size limit")
            payload = json.loads(contents.decode("utf-8"))
    except HTTPError as exc:
        raise DoclingError(f"Docling Serve returned HTTP {exc.code}") from exc
    except (URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError) as exc:
        raise DoclingError("Docling Serve request failed or returned invalid JSON") from exc

    if not isinstance(payload, dict) or payload.get("status") not in ("success", "partial_success"):
        raise DoclingError("Docling Serve did not complete the conversion")
    document = payload.get("document")
    markdown = document.get("md_content") if isinstance(document, dict) else None
    if not isinstance(markdown, str):
        raise DoclingError("Docling Serve returned no Markdown document")
    result = {"markdown": markdown.strip()}
    segments = _document_segments(document) if kind == "audio" else []
    if not segments:
        segments = _timed_segments(markdown)
    if segments:
        result["segments"] = segments
    return result
