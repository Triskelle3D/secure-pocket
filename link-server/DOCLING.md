# Secure Pocket Link and Docling Serve

The root `compose.yaml` runs Link beside a thin image based on the official CPU
Docling Serve release (`ghcr.io/docling-project/docling-serve-cpu:v1.38.2`).
The upstream base supports `linux/amd64` and `linux/arm64`. The derived image
adds upstream `openai-whisper==20250625` and bundled `imageio-ffmpeg==0.6.0`;
the stock Serve image omits Whisper ASR and an ffmpeg executable. Whisper is
installed without its optional WhisperS2T/triton accelerator dependency, which
does not have an arm64 wheel. All Docling code remains upstream; the build adds
runtime dependencies only. This stack does not use the Teczone image or fork
Docling's processing code.

Start the stack from the repository root:

```sh
docker compose up --build -d
```

Link stores recordings in `/data`, settings in `/config`, and its encryption
key in `/keys`, backed by the persistent `link-library`, `link-config`, and
`link-key` named volumes. Docling stores its model cache in `docling-models`
mounted at `/models`. Containers run as non-root users, drop Linux capabilities, prohibit
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
port; Link reaches it at `https://docling:5001`. Before either service starts,
a network-isolated helper creates a persistent private CA, a Docling server
certificate, and a random Docling API key. It renews the leaf certificate
before expiry; Docling detects the renewed certificate and restarts its
serving process to load it. Link trusts only the CA and sends the API key over
TLS; Docling rejects API requests without that key. The private CA key is
mounted only into the helper. The Docling server key, public CA certificate,
and API key are mounted read-only into the services that need them. The first
Compose start waits for provisioning before starting Link or Docling.

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

## Use your own Docling TLS certificate

TLS is enabled by default. To replace the generated server certificate with
one issued by your own CA, provide a CA certificate and a Docling server
certificate/key. The server certificate must contain the DNS subject
alternative name `docling`; keep private keys outside the repository. The CA
certificate must validate the Docling certificate.

```sh
DOCLING_TLS_CA_FILE=/absolute/path/ca.crt \
DOCLING_TLS_CERT_FILE=/absolute/path/docling.crt \
DOCLING_TLS_KEY_FILE=/absolute/path/docling.key \
docker compose -f compose.yaml -f compose.docling-tls.yaml up --build -d
```

This overlay replaces the generated certificate mounts; the standard Docling
startup wrapper continues to provide TLS and API-key authentication. It also
configures Link and the Docling health check to validate your CA. Link automatically upgrades the previous
default `http://docling:5001` setting to HTTPS. Custom non-loopback Docling
addresses must use HTTPS; HTTP is allowed only for a service on the same
computer. If a TLS health check fails, verify the CA and certificate SAN.

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
