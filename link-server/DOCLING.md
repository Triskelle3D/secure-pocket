# Secure Pocket Link and Docling Serve

The root `compose.yaml` runs Link alongside a thin image based on the official
CPU Docling Serve release (`ghcr.io/docling-project/docling-serve-cpu:v1.38.2`).
The pinned upstream base supports both `linux/amd64` and `linux/arm64`. The
derived image adds upstream `openai-whisper==20250625` and bundled
`imageio-ffmpeg==0.6.0`; the stock Serve image omits Whisper ASR and an ffmpeg
executable, so it cannot process uploaded audio without these runtime pieces.
Triton is optional for Whisper's CPU mode and has no arm64 wheel, so the build
installs Whisper without its declared optional accelerator dependency. All
Docling code remains upstream; no Docker socket is used.

Start the stack from the repository root:

```sh
docker compose up --build -d
```

Secure Pocket Link keeps its library at `/data` and settings at `/config`,
backed by the persistent `link-library` and `link-config` named volumes. The
Docling model volume is also persistent: Docling uses `/models` as its home,
so its normal model cache survives container replacement. Docling
Serve is only exposed on the Compose network at `http://docling:5001`. Link's
Docling URL setting can be left empty to use that default or set to another
Docling Serve URL when using a separately managed instance.

To store the Link library in a chosen host folder, create it with write
permission for the Link container user and start with the optional override:

```sh
LINK_LIBRARY_HOST_PATH=/absolute/path/to/library \
  docker compose -f compose.yaml -f compose.host-library.yaml up --build -d
```

The override mounts that folder at `/data`; settings and Docling models still
use their named volumes.

The Android API remains available to the LAN at `http://<computer>:8732`.
The desktop settings page is `http://127.0.0.1:8733/admin/`; only its host
loopback port is published. If either port is already occupied, override
`LINK_PUBLIC_API_PORT` or `LINK_PUBLIC_ADMIN_PORT`, for example:

```sh
LINK_PUBLIC_API_PORT=28732 LINK_PUBLIC_ADMIN_PORT=28733 docker compose up --build -d
```

Link's `POST /docling/pre` and `POST /docling/post` handlers pass the uploaded
audio or Markdown to Docling Serve's standard `/v1/convert/file` API. The
audio response preserves Docling's Markdown and includes timestamped segments
when its ASR output contains timestamps. Model availability depends on first
download completing and network access to upstream model hosting.

To stop the containers while retaining data, run `docker compose down`. To
remove the library, settings, and downloaded model cache as well, run
`docker compose down --volumes`.
