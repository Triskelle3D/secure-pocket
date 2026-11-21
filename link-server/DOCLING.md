# Secure Pocket Link and Docling Serve

The root `compose.yaml` runs Link beside a thin image based on the official CPU
Docling Serve release (`ghcr.io/docling-project/docling-serve-cpu:v1.38.2`).
The upstream base supports `linux/amd64` and `linux/arm64`. The derived image
adds upstream `openai-whisper==20250625` and bundled `imageio-ffmpeg==0.6.0`;
the stock Serve image omits Whisper ASR and an ffmpeg executable. Whisper is
installed without its optional WhisperS2T/triton accelerator dependency, which
does not have an arm64 wheel. All Docling code remains upstream; the build adds
runtime dependencies only.

Start the stack from the repository root:

```sh
docker compose up --build -d
```

Link stores recordings in `/data` and settings and its encryption key in
`/config`, backed by the persistent `link-library` and `link-config` named
volumes. Docling stores its model cache in `docling-models` mounted at
`/models`. Containers run as non-root users, drop Linux capabilities, prohibit
privilege escalation, and have read-only root filesystems with bounded writable
temporary storage. Link accepts uploads up to 128 MiB; its `/tmp` tmpfs defaults
to 512 MiB for an incoming upload, encrypted staging, and temporary verified
decrypt reads. Docling's `/tmp` tmpfs defaults to 2 GiB for conversions. CPU,
memory, and temporary storage limits are
set in Compose and can be adjusted with `LINK_MEMORY_LIMIT`, `LINK_CPU_LIMIT`,
`LINK_TMP_SIZE`, `DOCLING_MEMORY_LIMIT`, `DOCLING_CPU_LIMIT`,
`DOCLING_TMP_SIZE`, and `DOCLING_SHM_SIZE`. Increase the matching memory limit
when increasing a tmpfs size or processing large uploads.

The services share a dedicated Docker bridge. Docling has no published host
port; it is reached at `docling:5001`. The default Link-to-Docling connection
uses HTTP on this private bridge to support first-run model downloads. Enable
TLS between Link and Docling with the optional overlay below.

## Host folders and API access

To store the Link library in a chosen host folder, create it with write
permission for the Link container user and start with the optional override:

```sh
LINK_LIBRARY_HOST_PATH=/absolute/path/to/library \
  docker compose -f compose.yaml -f compose.host-library.yaml up --build -d
```

The override mounts that folder at `/data`; settings and Docling models still
use their named volumes.

The API and settings page are published on host loopback only by default. A
trusted TLS reverse proxy or tailnet forwarder on this computer can reach the
API backend at `127.0.0.1:8732`; settings are at
`http://127.0.0.1:8733/admin/`. Set `LINK_PUBLIC_URL` to the exact public HTTPS
origin (including a non-default port when needed), for example
`https://pocket.example.net`, so pairing displays the encrypted URL. Keep the
proxy listener and certificate management on a trusted host or tailnet.

For direct Link API TLS, provide a trusted certificate and key, then use the
direct TLS overlay. The API certificate must be trusted by Android and include
the DNS name in `LINK_PUBLIC_URL`:

```sh
LINK_BIND_ADDRESS=0.0.0.0 \
LINK_PUBLIC_URL=https://pocket.example.net \
LINK_TLS_CERT_FILE=/absolute/path/link.crt \
LINK_TLS_KEY_FILE=/absolute/path/link.key \
docker compose -f compose.yaml -f compose.link-tls.yaml up --build -d
```

The settings port remains loopback-only. Never expose the plain HTTP proxy
backend to the LAN.

If either port is already occupied, override `LINK_PUBLIC_API_PORT` or
`LINK_PUBLIC_ADMIN_PORT`, for example:

```sh
LINK_PUBLIC_API_PORT=28732 LINK_PUBLIC_ADMIN_PORT=28733 docker compose up --build -d
```

## Optional TLS for Link to Docling

The base stack keeps Docling off host ports but uses unencrypted HTTP over the
private Docker bridge. To enable authenticated TLS, provide a CA certificate
and a Docling server certificate/key. The server certificate must contain the
DNS subject alternative name `docling`; keep all private keys outside the
repository. The CA certificate must validate the Docling certificate.

```sh
DOCLING_TLS_CA_FILE=/absolute/path/ca.crt \
DOCLING_TLS_CERT_FILE=/absolute/path/docling.crt \
DOCLING_TLS_KEY_FILE=/absolute/path/docling.key \
docker compose -f compose.yaml -f compose.docling-tls.yaml up --build -d
```

This overlay mounts the files read-only, enables Docling Serve's upstream
Uvicorn TLS support, and configures Link's standard HTTPS client to validate
the provided CA. On a fresh Link config, the default service URL is
`https://docling:5001`. If the existing `/config/config.json` has a saved
Docling URL, update it in Link settings to `https://docling:5001`; saved
settings take precedence over defaults. A failed TLS health check prevents
Link from starting, so verify the CA and certificate SAN before deployment.

## Model download and lifecycle

Docling downloads its models from upstream hosts on first use. The private
Docker bridge allows outbound downloads while keeping the Docling listener
unpublished. After the cache is populated, it persists across container
replacements in `docling-models`. Model download availability and CPU/memory
requirements depend on the selected conversion and upstream hosting.

To stop containers while retaining data, run `docker compose down`. To remove
the library, settings, encryption key, and model cache as well, run
`docker compose down --volumes`.

## Container image updates

Both Dockerfiles pin base images by multi-architecture digest. Review upstream
releases and vulnerability scans before refreshing those digests and the
versioned Python packages in `Dockerfile.docling`. The service image is
intentionally based on upstream Docling Serve; it does not fork or modify its
processing implementation.
